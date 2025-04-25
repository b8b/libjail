extern crate exec;

use std::env;
use std::fs;
use exec::Command;

fn main() {
    // Get the path to the running executable
    let exe_path = env::current_exe()
        .expect("Failed to get current executable path");

    let main_class = match exe_path.file_stem().and_then(|s| s.to_str()) {
        Some("intercept-oci-runtime") => "org.cikit.oci.GenericInterceptor",
        Some("intercept-ocijail") => "org.cikit.oci.jail.OciJailInterceptor",
        Some("intercept-rcjail") => "org.cikit.oci.jail.RcJailInterceptor",
        Some("jpkg") => "org.cikit.oci.jail.JPkgCommand",
        _ => panic!("Invalid command name"),
    };

    let rexe_path = fs::canonicalize(exe_path)
        .expect("Failed to canonicalize executable path");

    // Get the parent directory
    let dir = rexe_path
        .parent()
        .expect("Failed to get parent directory")
        .to_path_buf();

    // Build the path to the java binary in the same directory
    let java_path = dir.join("java");

    // Prepare the command arguments
    let mut args: Vec<String> = Vec::new();
    args.push("-m".to_string());
    args.push(format!("org.cikit.oci.interceptor/{}", main_class));
    args.extend(env::args().skip(1)); // add all user-passed args

    // Replace current process with the new one
    let err = Command::new(java_path)
        .args(&args)
        .exec(); // Only available on Unix

    // If exec fails, print error and exit
    eprintln!("Failed to exec java: {:?}", err);
    std::process::exit(1);
}
