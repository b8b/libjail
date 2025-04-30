extern crate clap;
extern crate jail;
extern crate sysctl;
extern crate libc;
extern crate nix;

use clap::{Parser, Subcommand};

#[derive(Parser)]
struct Cli {
    #[arg(short)]
    jid: String,
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    MntInfo,
    Unmount {
        #[arg(short, long)]
        fsid: String,
    },
    DestroyVmm {
        #[arg(short, long)]
        name: String,
    },
}

use jail::RunningJail;
use sysctl::{Ctl, CtlValue, Sysctl};

use std::ffi::CString;
use libc::{c_char, c_int};
use nix::errno::Errno;

unsafe extern "C" {
    fn unmount(dir: *const c_char, flags: c_int) -> c_int;
}

fn main() {
    let args = Cli::parse();

    let running_jail = RunningJail::from_name(args.jid.as_str()).unwrap();
    running_jail.attach().unwrap();

    match args.command {
        Commands::MntInfo => {
            let ctl = Ctl::new("security.jail.mntinfojson").unwrap();
            let val = ctl.value_string().unwrap();
            println!("{}", val);
        }
        Commands::Unmount { fsid } => {
            let dir = CString::new(fsid).expect("CString::new failed");
            let flags = 0x0000000008080000; // MNT_BYFSID|MNT_FORCE
            let result = unsafe { unmount(dir.as_ptr(), flags) };
            if result != 0 {
                let e = Errno::last();
                println!("Unmount failed with error code: {}", e);
                std::process::exit(1);
            }
        }
        Commands::DestroyVmm { name } => {
            let ctl = Ctl::new("hw.vmm.destroy").unwrap();
            let val = CtlValue::String(name);
            ctl.set_value(val).unwrap();
        }
    }
}
