// Copyright (C) 2023 The Android Open Source Project
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

use anyhow::{anyhow, bail, Result};
use cargo::{
    core::{Manifest, SourceId},
    util::toml::read_manifest,
    Config,
};
use name_and_version::{NameAndVersionRef, NamedAndVersioned};
use rooted_path::RootedPath;
use semver::Version;

use crate::CrateError;

#[derive(Debug, Clone)]
pub struct Crate {
    manifest: Manifest,
    path: RootedPath,
}

impl NamedAndVersioned for Crate {
    fn name(&self) -> &str {
        self.manifest.name().as_str()
    }
    fn version(&self) -> &Version {
        self.manifest.version()
    }
    fn key(&self) -> NameAndVersionRef {
        NameAndVersionRef::new(self.name(), self.version())
    }
}

impl Crate {
    pub fn new(manifest: Manifest, path: RootedPath) -> Crate {
        Crate { manifest, path }
    }
    pub fn from(manifest_dir: RootedPath) -> Result<Crate> {
        let manifest_path = manifest_dir.abs().join("Cargo.toml");
        let source_id = SourceId::for_path(manifest_dir.abs())?;
        let (manifest, _nested) = read_manifest(
            manifest_dir.join("Cargo.toml")?.as_ref(),
            source_id,
            &Config::default()?,
        )?;
        match manifest {
            cargo::core::EitherManifest::Real(r) => {
                let krate = Crate::new(r, manifest_dir);
                let metadata = cargo_metadata::MetadataCommand::new()
                    .manifest_path(manifest_path)
                    .no_deps()
                    .other_options(["--frozen".to_string()])
                    .exec()?;

                println!("got here");
                if metadata.packages.len() != 1 {
                    bail!("Expected one package, got {}", metadata.packages.len());
                }

                let package = &metadata.packages[0];
                if package.name != krate.name() {
                    println!("name: {} vs {}", krate.name(), package.name);
                }
                if &package.version != krate.version() {
                    println!("version: {} vs {}", krate.version(), package.version);
                }
                let description = package.description.as_deref().unwrap_or("");
                if description != krate.description() {
                    println!("description: {} vs {}", krate.description(), description);
                }
                if package.license.as_deref() != krate.license() {
                    println!(
                        "license: {} vs {}",
                        krate.license().unwrap_or("<none>"),
                        package.license.as_deref().unwrap_or("<none>")
                    );
                }
                if package.repository.as_deref() != krate.repository() {
                    println!(
                        "repository: {} vs {}",
                        krate.repository().unwrap_or("<none>"),
                        package.repository.as_deref().unwrap_or("<none>")
                    );
                }

                Ok(krate)
            }
            cargo::core::EitherManifest::Virtual(_) => {
                Err(anyhow!(CrateError::VirtualCrate(manifest_dir.as_ref().to_path_buf())))
            }
        }
    }

    pub fn description(&self) -> &str {
        self.manifest.metadata().description.as_deref().unwrap_or("")
    }
    pub fn license(&self) -> Option<&str> {
        self.manifest.metadata().license.as_deref()
    }
    pub fn repository(&self) -> Option<&str> {
        self.manifest.metadata().repository.as_deref()
    }
    pub fn path(&self) -> &RootedPath {
        &self.path
    }
}

#[cfg(test)]
mod tests {
    use std::{
        fs::{create_dir, write},
        path::Path,
    };

    use super::*;
    use anyhow::anyhow;
    use tempfile::tempdir;

    fn write_test_manifest(temp_crate_dir: &Path, name: &str, version: &str) -> Result<RootedPath> {
        let temp_crate_dir = RootedPath::new("/", temp_crate_dir.strip_prefix("/")?)?;
        write(
            temp_crate_dir.join("Cargo.toml")?,
            format!("[package]\nname = \"{}\"\nversion = \"{}\"\n", name, version),
        )?;
        let lib_rs = temp_crate_dir.join("src/lib.rs")?;
        create_dir(lib_rs.abs().parent().ok_or(anyhow!("Failed to get parent"))?)?;
        write(lib_rs, "// foo")?;
        Ok(temp_crate_dir)
    }

    #[test]
    fn test_from_and_properties() -> Result<()> {
        let temp_crate_dir = tempdir()?;
        let manifest_dir = write_test_manifest(temp_crate_dir.path(), "foo", "1.2.0")?;
        let krate = Crate::from(manifest_dir)?;
        assert_eq!(krate.name(), "foo");
        assert_eq!(krate.version().to_string(), "1.2.0");
        Ok(())
    }

    #[test]
    fn test_from_error() -> Result<()> {
        let temp_crate_dir = tempdir()?;
        let manifest_dir = write_test_manifest(temp_crate_dir.path(), "foo", "1.2.0")?;
        assert!(Crate::from(manifest_dir.join("blah")?).is_err());
        Ok(())
    }
}
