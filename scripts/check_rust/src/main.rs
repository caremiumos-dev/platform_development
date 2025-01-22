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
use serde::Serialize;
use serde_json::Deserializer;
use std::collections::HashMap;
use std::fs::File;
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

// https://doc.rust-lang.org/nightly/nightly-rustc/rustc_errors/json/struct.Diagnostic.html
#[derive(Serialize, Deserialize, Clone, Debug, Eq, PartialEq, Hash)]
struct Diagnostic {
    message: String,
    code: Option<DiagnosticCode>,
    level: String,
    spans: Vec<DiagnosticSpan>,
    children: Vec<Diagnostic>,
    rendered: Option<String>,
}

// https://doc.rust-lang.org/nightly/nightly-rustc/rustc_errors/json/struct.DiagnosticSpan.html
#[derive(Serialize, Deserialize, Clone, Debug, Eq, PartialEq, Hash)]
struct DiagnosticSpan {
    file_name: PathBuf,
    byte_start: u32,
    byte_end: u32,
    line_start: usize,
    line_end: usize,
    column_start: usize,
    column_end: usize,
    is_primary: bool,
    text: Vec<DiagnosticSpanLine>,
    label: Option<String>,
    suggested_replacement: Option<String>,
    suggestion_applicability: Option<Applicability>,
    expansion: Option<Box<DiagnosticSpanMacroExpansion>>,
}

// https://doc.rust-lang.org/nightly/nightly-rustc/rustc_errors/json/struct.DiagnosticSpanMacroExpansion.html
#[derive(Serialize, Deserialize, Clone, Debug, Eq, PartialEq, Hash)]
struct DiagnosticSpanMacroExpansion {
    span: DiagnosticSpan,
    macro_decl_name: String,
    def_site_span: DiagnosticSpan,
}

// https://doc.rust-lang.org/nightly/nightly-rustc/rustc_errors/enum.Applicability.html
#[derive(Serialize, Deserialize, Clone, Debug, Eq, PartialEq, Hash)]
enum Applicability {
    MachineApplicable,
    MaybeIncorrect,
    HasPlaceholders,
    Unspecified,
}

// https://doc.rust-lang.org/nightly/nightly-rustc/rustc_errors/json/struct.DiagnosticCode.html
#[derive(Serialize, Deserialize, Clone, Debug, Eq, PartialEq, Hash)]
struct DiagnosticCode {
    code: String,
    explanation: Option<String>,
}

// https://doc.rust-lang.org/nightly/nightly-rustc/rustc_errors/json/struct.DiagnosticSpanLine.html
#[derive(Serialize, Deserialize, Clone, Debug, Eq, PartialEq, Hash)]
struct DiagnosticSpanLine {
    text: String,
    highlight_start: usize,
    highlight_end: usize,
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

fn make_diagnostic_absolute(diagnostic: &mut Diagnostic, base_dir: &Path) {
    for span in diagnostic.spans.iter_mut() {
        make_span_absolute(span, base_dir);
    }

    for diagnostic in diagnostic.children.iter_mut() {
        make_diagnostic_absolute(diagnostic, base_dir);
    }
}

fn make_span_absolute(span: &mut DiagnosticSpan, base_dir: &Path) {
    span.file_name = base_dir.join(&span.file_name);

    if let Some(expansion) = &mut span.expansion {
        make_span_absolute(&mut expansion.def_site_span, base_dir);
        make_span_absolute(&mut expansion.span, base_dir);
    }
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
    let host_platform = exe_path
        .ancestors()
        .nth(2)
        .with_context(|| format!("Path {} was malformed", exe_path.display()))?
        .file_name()
        .with_context(|| format!("Path {} was malformed", exe_path.display()))?;

    let soong_env = load_soong_env_variables(android_build_top)?;
    let target_product = soong_env
        .get("TARGET_PRODUCT")
        .ok_or(anyhow!("TARGET_PRODUCT not found. Please run lunch <target> && m nothing"))?;

    let source_file = source_file.strip_prefix(android_build_top).with_context(|| {
        format!(
            "File {} is not contained within AOSP project {}",
            source_file.display(),
            android_build_top.display()
        )
    })?;

    let check_targets = load_check_targets(android_build_top, source_file)?;

    let ninja_path = android_build_top
        .join("prebuilts")
        .join("build-tools")
        .join(host_platform)
        .join("bin")
        .join("ninja");

    let mut ninja_command = Command::new(ninja_path);
    ninja_command
        .arg("-f")
        .arg(android_build_top.join("out").join(format!("combined-{}.ninja", target_product)))
        .args(["-k", "0"])
        .args(&check_targets)
        // TMPDIR is needed by build/soong/scripts/mkcratersp.py:58. Since not all systems
        // set TMPDIR, we will set it here to ensure we don't fail
        .env("TMPDIR", std::env::temp_dir());

    let Output { status: ninja_status, stderr: ninja_stderr, stdout: ninja_stdout } =
        ninja_command.output().with_context(|| format!("{ninja_command:?} failed"))?;

    std::io::stderr().write_all(&ninja_stdout).expect("Could not write ninja stdout to stderr");
    std::io::stderr().write_all(&ninja_stderr).expect("Could not write ninja stderr to stderr");

    // If ninja does not have a return code, then it was killed by a signal. In that case just
    // exit with an error.
    ensure!(ninja_status.code().is_some(), "{ninja_command:?} failed to return a code");

    let mut diagnostics = Vec::new();
    for check_target in &check_targets {
        let check_output = android_build_top.join(error_file(check_target));
        let reader = BufReader::new(
            File::open(&check_output)
                .with_context(|| format!("Failed to open {}", check_output.display()))?,
        );

        // Since soong builds from AOSP_BUILD_TOP, the DiagnosticSpan.file_name field in the error/warning JSON
        // will be reported as relative to AOSP_BUILD_TOP. Relative paths are interpreted as relative to the
        // "rust-project.json" file [0]. However because "rust-project.json" appears in out/soong, we will
        // rewrite any relative paths to absolute paths to ensure that we are not dependant on the location of
        // "rust-project.json".
        //
        // [0]: https://rust-analyzer.github.io/manual.html#non-cargo-based-projects
        for line_result in Deserializer::from_reader(reader).into_iter() {
            let mut diagnostic = line_result.with_context(|| {
                format!("Failed to parse Diagnostic: {}", check_output.display())
            })?;

            make_diagnostic_absolute(&mut diagnostic, android_build_top);

            // We have a small amount of diagnostics, so use a linear search instead of hashing.
            if !diagnostics.contains(&diagnostic) {
                diagnostics.push(diagnostic);
            }
        }
    }

    let mut stdout = std::io::stdout().lock();
    for diagnostic in diagnostics {
        serde_json::to_writer(&mut stdout, &diagnostic)?;
        writeln!(&mut stdout)?;
    }

    Ok(())
}
