use std::{
    collections::BTreeSet,
    path::Path,
    process::{Command, ExitStatus, Output},
    str::from_utf8,
    sync::LazyLock,
};

use anyhow::{anyhow, bail, Result};
use rand::seq::SliceRandom;
use rand::thread_rng;

pub trait SuccessOrError {
    fn success_or_error(self) -> Result<Self>
    where
        Self: std::marker::Sized;
}
impl SuccessOrError for ExitStatus {
    fn success_or_error(self) -> Result<Self> {
        if !self.success() {
            let exit_code =
                self.code().map(|code| format!("{}", code)).unwrap_or("(unknown)".to_string());
            Err(anyhow!("Process failed with exit code {}", exit_code))
        } else {
            Ok(self)
        }
    }
}
impl SuccessOrError for Output {
    fn success_or_error(self) -> Result<Self> {
        (&self).success_or_error()?;
        Ok(self)
    }
}
impl SuccessOrError for &Output {
    fn success_or_error(self) -> Result<Self> {
        if !self.status.success() {
            let exit_code = self
                .status
                .code()
                .map(|code| format!("{}", code))
                .unwrap_or("(unknown)".to_string());
            Err(anyhow!(
                "Process failed with exit code {}\nstdout:\n{}\nstderr:\n{}",
                exit_code,
                from_utf8(&self.stdout)?,
                from_utf8(&self.stderr)?
            ))
        } else {
            Ok(self)
        }
    }
}

fn cleanup_monorepo(monorepo_path: &Path) -> Result<()> {
    Command::new("git")
        .args(["restore", "--staged", "."])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;
    Command::new("git")
        .args(["restore", "."])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;

    Command::new("git")
        .args(["clean", "-f", "-d"])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;

    Command::new("repo")
        .args(["start", "foo"])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;
    Command::new("repo")
        .args(["abandon", "foo", "."])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;

    let output = Command::new("git")
        .args(["status", "--porcelain", "."])
        .current_dir(monorepo_path)
        .output()?
        .success_or_error()?;
    if !output.stdout.is_empty() {
        bail!("Monorepo {} has uncommitted changes", monorepo_path.display());
    }

    Ok(())
}

fn sync_monorepo(monorepo_path: &Path) -> Result<()> {
    Command::new("repo")
        .args(["sync", "."])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;
    Ok(())
}

fn sync_to_green(monorepo_path: &Path) -> Result<()> {
    Command::new("prodcertstatus").spawn()?.wait()?.success_or_error()?;
    Command::new("/google/data/ro/projects/android/smartsync_login")
        .spawn()?
        .wait()?
        .success_or_error()?;

    let output = Command::new("/google/data/ro/projects/android/ab")
        .args([
            "lkgb",
            "--branch=aosp-main",
            "--target=aosp_arm64-trunk_staging-userdebug",
            "--raw",
            "--custom_raw_format={o[buildId]}",
        ])
        .output()?
        .success_or_error()?;
    let bid = from_utf8(&output.stdout)?.trim();
    println!("bid = {}", bid);

    Command::new("/google/data/ro/projects/android/smartsync_repo")
        .args(["sync", "-j99", "-t", bid])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;

    Command::new("repo")
        .args(["sync", "."])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;

    Ok(())
}

fn get_suggestions(monorepo_path: &Path) -> Result<Vec<(String, String)>> {
    let output = Command::new(monorepo_path.join("crate_tool"))
        .args(["suggest-updates", "--patches"])
        .current_dir(monorepo_path)
        .output()?
        .success_or_error()?;
    let mut suggestions = from_utf8(&output.stdout)?
        .trim()
        .lines()
        .map(|suggestion| {
            let words = suggestion.split_whitespace().collect::<Vec<_>>();
            if words.len() != 6 {
                println!("Failed to parse suggestion {}", suggestion);
            }
            (words[2].to_string(), words[5].to_string())
        })
        .collect::<Vec<_>>();
    let mut rng = thread_rng();
    suggestions.shuffle(&mut rng);

    Ok(suggestions)
}

fn try_update(
    repo_root: &Path,
    monorepo_path: &Path,
    crate_name: &str,
    version: &str,
) -> Result<()> {
    println!("Trying to update {} to {}", crate_name, version);
    Command::new(monorepo_path.join("crate_tool"))
        .args(["update", crate_name, version])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;

    Command::new("/usr/bin/bash")
        .args([
            "-c",
            format!(
                "source {}/build/envsetup.sh && lunch aosp_husky-trunk_staging-eng && mm && m",
                repo_root.display()
            )
            .as_str(),
        ])
        .env_remove("OUT_DIR")
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;

    Command::new("repo")
        .args(["start", format!("automatic-crate-update-{}-{}", crate_name, version).as_str()])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;
    Command::new("git")
        .args(["add", "."])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;
    Command::new("git")
        .args(["commit", "-m", format!("Update {} to {}\n\nTest: m", crate_name, version).as_str()])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;
    Command::new("repo")
        .args([
            "upload",
            "-c",
            ".",
            "-y",
            "-o",
            "banned-words~skip",
            "-o",
            "nokeycheck",
            "--label",
            "Presubmit-Ready+1",
        ])
        .current_dir(monorepo_path)
        .spawn()?
        .wait()?
        .success_or_error()?;

    Ok(())
}

static DENYLIST: LazyLock<BTreeSet<&'static str>> = LazyLock::new(|| {
    BTreeSet::from([
        // Paired crates that need to be updated together
        "protobuf-support",
        "protobuf",
        "ptr_meta",
        "ptr_meta_derive",
        "regex-automata",
        "serde_derive",
        "serde",
        "thiserror-impl",
        "thiserror",
        "tikv-jemalloc-sys",
        "tikv-jemallocator",
        "zerocopy-derive",
        "zerocopy",
        // Patch failures
        "anyhow",
        "foreign-types",
        "untrusted",
        // Test failures
        "ash",
        "bindgen",
        "bstr",
        "config",
        "half",
        "matchit",
        "mls-rs-core",
        "named-lock",
        "p9_wire_format_derive",
        // "tinyvec",  // Tests don't build due to missing dev-dependencies.
        "tokio-test",
        // "toml_edit",  // Build failure in winnow.
        "tower",
        "unicode-width", // Emoji test seems to need extra data downloaded.
        // "virtio-bindings",  // Build failure
        // "vm-memory",  // Build failure due to vhost.
        // "winnow",  // Build failure due to toml_edit.
        "xml-rs", // Unit test failure in serde-xml-rs.
        // Other
        "rusqlite",
        "libz-sys", // Needs an update.
    ])
});

fn main() -> Result<()> {
    let repo_root =
        Path::new("/usr/local/google/home/jamesfarrell/src/aosp-main-for-crate-updates");
    let monorepo_path = repo_root.join("external/rust/android-crates-io");

    cleanup_monorepo(&monorepo_path)?;

    sync_to_green(&monorepo_path)?;
    sync_monorepo(&monorepo_path)?;

    Command::new("/usr/bin/bash")
        .args([
            "-c",
            "source build/envsetup.sh && lunch aosp_husky-trunk_staging-eng && m cargo_embargo",
        ])
        .env_remove("OUT_DIR")
        .current_dir(repo_root)
        .spawn()?
        .wait()?
        .success_or_error()?;

    for (crate_name, version) in get_suggestions(&monorepo_path)?.iter() {
        if DENYLIST.contains(crate_name.as_str()) {
            println!("Skipping {crate_name} (on deny list)");
            continue;
        }
        cleanup_monorepo(&monorepo_path)?;
        sync_monorepo(&monorepo_path)?;
        let success =
            try_update(repo_root, &monorepo_path, crate_name.as_str(), version.as_str()).is_ok();
        // TODO: record to updates_tried.
    }

    Ok(())
}
