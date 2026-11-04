use clap::{ArgGroup, Parser};
use std::fs::{self, File};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

const ESP_TYPE_GUID: [u8; 16] = [
    0x28, 0x73, 0x2a, 0xc1, 0x1f, 0xf8, 0xd2, 0x11, 0xba, 0x4b, 0x00, 0xa0, 0xc9, 0x3e, 0xc9, 0x3b,
];

#[derive(Parser)]
#[command(group(
    ArgGroup::new("action")
        .required(true)
        .args(["source", "list", "add"])
))]
struct Cli {
    #[arg(long)]
    image: PathBuf,

    #[arg(long, default_value_t = 1)]
    partition: u32,

    #[arg(long, value_name = "DIR")]
    source: Option<PathBuf>,

    #[arg(long)]
    list: bool,

    #[arg(long, value_name = "DIR")]
    add: Option<PathBuf>,

    #[arg(long, default_value = "EFISYS")]
    label: String,
}

struct Window<'a> {
    file: &'a mut File,
    base: u64,
    len: u64,
    pos: u64,
}

impl<'a> Window<'a> {
    fn new(file: &'a mut File, base: u64, len: u64) -> Self {
        Self {
            file,
            base,
            len,
            pos: 0,
        }
    }
}

impl Read for Window<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.len.saturating_sub(self.pos).min(buf.len() as u64) as usize;
        if n == 0 {
            return Ok(0);
        }
        self.file.seek(SeekFrom::Start(self.base + self.pos))?;
        let n = self.file.read(&mut buf[..n])?;
        self.pos += n as u64;
        Ok(n)
    }
}

impl Write for Window<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.len.saturating_sub(self.pos).min(buf.len() as u64) as usize;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::WriteZero, "end of partition"));
        }
        self.file.seek(SeekFrom::Start(self.base + self.pos))?;
        let n = self.file.write(&buf[..n])?;
        self.pos += n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.file.flush()
    }
}

impl Seek for Window<'_> {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let target = match pos {
            SeekFrom::Start(off) => off as i64,
            SeekFrom::End(off) => self.len as i64 + off,
            SeekFrom::Current(off) => self.pos as i64 + off,
        };
        if target < 0 || target as u64 > self.len {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "seek out of partition",
            ));
        }
        self.pos = target as u64;
        Ok(self.pos)
    }
}

fn sorted_entries(dir: &Path) -> io::Result<Vec<fs::DirEntry>> {
    let mut entries = fs::read_dir(dir)?.collect::<Result<Vec<_>, _>>()?;
    entries.sort_by_key(fs::DirEntry::file_name);
    Ok(entries)
}

fn entry_name(entry: &fs::DirEntry) -> io::Result<String> {
    entry
        .file_name()
        .into_string()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "non-UTF-8 file name"))
}

fn is_dot(name: &str) -> bool {
    name == "." || name == ".."
}

fn remove_tree(dir: &fatfs::Dir<'_, Window<'_>>, name: &str) -> io::Result<()> {
    let sub = dir.open_dir(name)?;
    for entry in sub.iter() {
        let entry = entry?;
        let name = entry.file_name();
        if is_dot(&name) {
            continue;
        }
        if entry.is_dir() {
            remove_tree(&sub, &name)?;
        } else {
            sub.remove(&name)?;
        }
    }
    dir.remove(name)
}

fn replace_into(dir: &fatfs::Dir<'_, Window<'_>>, src: &Path) -> io::Result<()> {
    for entry in sorted_entries(src)? {
        let name = entry_name(&entry)?;
        let existing = dir
            .iter()
            .filter_map(Result::ok)
            .find(|e| e.file_name() == name);
        if entry.file_type()?.is_dir() {
            if existing.is_some_and(|e| !e.is_dir()) {
                dir.remove(&name)?;
            }
            replace_into(&dir.create_dir(&name)?, &entry.path())?;
        } else {
            if existing.is_some_and(|e| e.is_dir()) {
                remove_tree(dir, &name)?;
            }
            let mut out = dir.create_file(&name)?;
            out.truncate()?;
            io::copy(&mut File::open(entry.path())?, &mut out)?;
            out.flush()?;
        }
    }
    Ok(())
}

fn list_tree(dir: &fatfs::Dir<'_, Window<'_>>, prefix: &str) -> io::Result<()> {
    let mut entries = dir.iter().collect::<Result<Vec<_>, _>>()?;
    entries.sort_by_key(fatfs::DirEntry::file_name);
    for entry in entries {
        let name = entry.file_name();
        if is_dot(&name) {
            continue;
        }
        let path = format!("{prefix}/{name}");
        if entry.is_dir() {
            println!("d {:>12} {path}", 0);
            list_tree(&entry.to_dir(), &path)?;
        } else {
            println!("f {:>12} {path}", entry.len());
        }
    }
    Ok(())
}

fn main() {
    if let Err(err) = run() {
        eprintln!("espadm: {err}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();

    let writable = cli.list as u8 == 0;
    let mut file = File::options()
        .read(true)
        .write(writable)
        .open(&cli.image)?;

    let gpt = gptman::GPT::find_from(&mut file)?;
    let entry = gpt
        .iter()
        .find(|(index, _)| *index == cli.partition)
        .map(|(_, entry)| entry)
        .ok_or_else(|| format!("partition {} is out of range", cli.partition))?;
    if !entry.is_used() {
        return Err(format!("partition {} is unused", cli.partition).into());
    }
    if entry.partition_type_guid != ESP_TYPE_GUID {
        eprintln!(
            "warning: partition {} is not an EFI system partition",
            cli.partition
        );
    }

    let base = entry.starting_lba * gpt.sector_size;
    let len = entry.size()? * gpt.sector_size;
    let window = Window::new(&mut file, base, len);

    if let Some(source) = &cli.source {
        let mut label = [b' '; 11];
        for (slot, byte) in label.iter_mut().zip(cli.label.bytes()) {
            *slot = byte.to_ascii_uppercase();
        }
        let mut window = window;
        fatfs::format_volume(
            &mut window,
            fatfs::FormatVolumeOptions::new()
                .fat_type(fatfs::FatType::Fat32)
                .volume_label(label),
        )?;
        let fs = fatfs::FileSystem::new(window, fatfs::FsOptions::new())?;
        replace_into(&fs.root_dir(), source)
            .map_err(|err| format!("populating from {}: {err}", source.display()))?;
        fs.unmount()?;
        return Ok(());
    }

    let fs = fatfs::FileSystem::new(window, fatfs::FsOptions::new())?;

    if cli.list {
        list_tree(&fs.root_dir(), "")?;
    } else if let Some(add) = &cli.add {
        replace_into(&fs.root_dir(), add)
            .map_err(|err| format!("adding from {}: {err}", add.display()))?;
    }

    fs.unmount()?;
    Ok(())
}
