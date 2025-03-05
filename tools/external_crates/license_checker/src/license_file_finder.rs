// Copyright (C) 2024 The Android Open Source Project
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

use std::{
    collections::BTreeSet,
    ffi::OsStr,
    path::{Path, PathBuf},
    sync::LazyLock,
};

use glob::{glob_with, MatchOptions};

use crate::Error;

static LICENSE_GLOBS: &[&str] = &[
    "LICENSE",
    "LICENCE",
    "LICENSE.*",
    "LICENSE-*",
    "LICENSES/*",
    "UNLICENSE",
    "COPYING",
    "third-party/chromium/LICENSE",
    "docs/LICENSE*",
];

pub(crate) fn find_license_files(path: impl AsRef<Path>) -> Result<BTreeSet<PathBuf>, Error> {
    multiglob(path, LICENSE_GLOBS.iter())
}

static LICENSE_PATTERNS: LazyLock<Vec<glob::Pattern>> =
    LazyLock::new(|| LICENSE_GLOBS.iter().map(|p| glob::Pattern::new(p).unwrap()).collect());

static MATCH_OPTIONS: LazyLock<MatchOptions> = LazyLock::new(|| {
    let mut options = MatchOptions::new();
    options.case_sensitive = false;
    options
});

pub(crate) fn is_findable(license_file: &OsStr) -> bool {
    LICENSE_PATTERNS.iter().any(|p| p.matches_path_with(Path::new(license_file), *MATCH_OPTIONS))
}

fn multiglob<T: AsRef<str>>(
    path: impl AsRef<Path>,
    patterns: impl Iterator<Item = T>,
) -> Result<BTreeSet<PathBuf>, Error> {
    let path = path.as_ref();
    let mut matches = BTreeSet::new();
    for pattern in patterns {
        let pattern = path.join(pattern.as_ref());
        for file in glob_with(
            pattern.to_str().ok_or(Error::PathToString(pattern.clone()))?,
            *MATCH_OPTIONS,
        )? {
            let file = file?;
            if !file.is_symlink() {
                matches.insert(file.strip_prefix(path)?.to_owned());
            }
        }
    }
    Ok(matches)
}
