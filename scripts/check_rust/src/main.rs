// Copyright (C) 2025 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Provides rust-analyzer integration for the rustc/clippy-driver binary.
//!
//! This is an intermediary between rust-analyzer and the soong/ninja build system.
//! This file should be called when a Rust source file is updated, it will then parse
//! the rust-target-mapping.json file to figure out what ninja target(s) need to be built,
//! build them and then parse the outputted error/warning diagnostics, expand the relative
//! paths in the diagnostics into absolute paths and echo the diagnostics echo them to stdout
//! for rust-analyzer to parse.

use anyhow::anyhow;
use anyhow::ensure;
use anyhow::Context as _;
use clap::Parser;
use serde::Deserialize;
use std::collections::HashMap;
use std::fs::File;
use std::io::BufRead as _;
use std::io::BufReader;
use std::io::Write as _;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use std::process::Output;

#[derive(Parser, Debug)]
#[clap()]
struct Args {
    /// The absolute path of the Rust source file to run rust_check on.
    source_file: PathBuf,
}

#[derive(Debug, Deserialize)]
struct SoongEnvironmentEntry {
    #[serde(rename = "Key")]
    key: String,

    #[serde(rename = "Value")]
    value: String,
}

#[derive(Debug, Deserialize)]
struct RustTargetMapping {
    check_target: String,
    source_dir: String,
}

fn load_soong_env_variables(android_build_top: &Path) -> anyhow::Result<HashMap<String, String>> {
    let soong_env_file =
        android_build_top.join("out").join("soong").join("soong.environment.available");
    let file =
        File::open(soong_env_file).context("Could not find soong.environment.available file")?;

    let entries: Vec<_> = serde_json::from_reader(BufReader::new(file))?;

    Ok(entries.into_iter().map(|SoongEnvironmentEntry { key, value }| (key, value)).collect())
}

fn load_check_targets(android_build_top: &Path, source_file: &Path) -> anyhow::Result<Vec<String>> {
    let path = android_build_top.join("out").join("soong").join("rust-target-mapping.json");
    let file = File::open(&path).with_context(|| format!("Could not open {}", path.display()))?;

    let mapping_entries: Vec<_> = serde_json::from_reader(BufReader::new(file))
        .with_context(|| format!("Could not parse {}", path.display()))?;
    Ok(mapping_entries
        .into_iter()
        .filter_map(
            |RustTargetMapping { check_target, source_dir }| {
                if source_file.starts_with(source_dir) {
                    Some(check_target)
                } else {
                    None
                }
            },
        )
        .collect())
}

/// Returns the file name of the error file for a given check target.
///
/// This is outputted by the ".checkJson" static build rule in the soong build system.
fn error_file(check_target: &str) -> String {
    format!("{}.error", check_target)
}

fn main() -> anyhow::Result<()> {
    let Args { source_file } = Parser::parse();

    // This outputted binary will always exist in the $ANDROID_BUILD_TOP/out/host/$HOST_PLATFORM/bin/check_rust
    let exe_path = std::env::current_exe().context("Failed to get current executable path")?;
    let android_build_top = exe_path
        .ancestors()
        .nth(5)
        .with_context(|| format!("Path {} was malformed", exe_path.display()))?;

    let soong_env = load_soong_env_variables(android_build_top)?;

    // Sanity check that the environment variables are set correctly.
    if (!soong_env.contains_key("TARGET_PRODUCT") && !soong_env.contains_key("TARGET_RELEASE"))
        || (!soong_env.contains_key("TARGET_BUILD_VARIANT"))
    {
        return Err(anyhow!(
            "Lunch variables were not set. Please run lunch <target> && m nothing"
        ));
    }

    let source_file = source_file.strip_prefix(android_build_top).with_context(|| {
        format!(
            "File {} is not contained within AOSP project {}",
            source_file.display(),
            android_build_top.display()
        )
    })?;

    let check_targets = load_check_targets(android_build_top, source_file)?;

    let soong_ui_path = android_build_top.join("build").join("soong").join("soong_ui.bash");

    // Include all relevant environment variables from "soong.environment.available" file. Since
    // soong_ui.bash will rebuild the ninja file if the environment variables change, we need to ensure
    // that we pass in the same environment variables that were used to build the ninja file to not cause
    // a rebuild.
    let mut soong_ui_cmd = Command::new(soong_ui_path);
    soong_ui_cmd
        .envs(
            soong_env.into_iter().filter(|(key, _)| {
                ["BUILD_", "SOONG_", "TARGET_"].iter().any(|s| key.starts_with(s))
            }),
        )
        .arg("--make-mode")
        .args(&check_targets);

    let Output { status: soong_ui_status, stderr: soong_ui_stderr, stdout: soong_ui_stdout } =
        soong_ui_cmd.output().with_context(|| format!("{soong_ui_cmd:?} failed"))?;

    std::io::stderr()
        .write_all(&soong_ui_stdout)
        .expect("Could not write soong_ui stdout to stderr");
    std::io::stderr()
        .write_all(&soong_ui_stderr)
        .expect("Could not write soong_ui stderr to stderr");

    // If ninja does not have a return code, then it was killed by a signal. In that case just
    // exit with an error.
    ensure!(soong_ui_status.code().is_some(), "{soong_ui_cmd:?} failed to return a code");

    let mut diagnostics = Vec::new();
    for check_target in &check_targets {
        let check_output = android_build_top.join(error_file(check_target));
        let reader = BufReader::new(
            File::open(&check_output)
                .with_context(|| format!("Failed to open {}", check_output.display()))?,
        );

        // Since soong builds from AOSP_BUILD_TOP, the DiagnosticSpan.file_name field in the error/warning JSON
        // will be reported as relative to AOSP_BUILD_TOP. Relative paths are interpreted as relative to the
        // "rust-project.json" file [0]. We expect the developer to set SOONG_LINK_RUST_PROJECT_TO=${AOSP_BUILD_TOP}
        // to ensure that all these relative paths are correct. Therefore we simply check for duplicate messages and
        // then echo them out to stdout for rust-analyzer to process.
        //
        // [0]: https://rust-analyzer.github.io/manual.html#non-cargo-based-projects
        for diagnostic_result in reader.lines() {
            let diagnostic = diagnostic_result.with_context(|| {
                format!("Failed to read diagnostic from {}", check_output.display())
            })?;

            // We have a small amount of diagnostics, so use a linear search instead of hashing.
            if !diagnostics.contains(&diagnostic) {
                diagnostics.push(diagnostic);
            }
        }
    }

    let mut stdout = std::io::stdout().lock();
    for diagnostic in diagnostics {
        writeln!(&mut stdout, "{diagnostic}")?;
    }

    Ok(())
}
