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

//! JSON file to store per-crate configuration.

use std::{fs::File, io, path::Path};

use serde::Deserialize;

/// A parsed android_config.json file
#[derive(Deserialize, Debug, Default, Clone)]
pub struct CrateConfig {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    deletions: Vec<Deletion>,

    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    licenses: Vec<License>,
}

#[derive(Deserialize, Debug, Clone)]
struct Deletion {
    path: String,
    _comment: Option<String>, // Strict JSON doesn't allow comments, so recognize a comment field.
}

#[derive(Deserialize, Debug, Clone)]
struct License {
    url: String,
    _comment: Option<String>, // Strict JSON doesn't allow comments, so recognize a comment field.
}

#[allow(missing_docs)]
#[derive(thiserror::Error, Debug)]
pub enum Error {
    #[error("JSON parse error")]
    JsonParseError(#[from] serde_json::Error),
    #[error("IO error")]
    IoError(#[from] io::Error),
}

impl CrateConfig {
    /// Read the android_config.json file in the specified directory.
    pub fn read(config_file: impl AsRef<Path>) -> Result<CrateConfig, Error> {
        let config_file = config_file.as_ref();
        if !config_file.exists() {
            return Ok(CrateConfig::default());
        }
        let file = File::open(config_file)?;
        let json: CrateConfig = serde_json::from_reader(io::BufReader::new(file))?;
        Ok(json)
    }
    /// Get an iterator over directories and files to delete.
    pub fn deletions(&self) -> impl Iterator<Item = &str> {
        self.deletions.iter().map(|d| d.path.as_str())
    }
    /// Get an iterator over license URLs.
    pub fn licenses(&self) -> impl Iterator<Item = &str> {
        self.licenses.iter().map(|l| l.url.as_str())
    }
}
