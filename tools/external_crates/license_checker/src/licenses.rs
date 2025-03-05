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

// TODO: Inexact names

use std::{
    collections::{BTreeMap, BTreeSet},
    ffi::OsString,
};

use spdx::{LicenseReq, Licensee};

use crate::{file_classifier, CrateLicenseSpecialCase, Error, License};

struct ParsedLicense {
    // The SPDX identifier, e.g. "Apache-2.0"
    req: LicenseReq,
    // The text of the license.
    raw_text: Option<String>,
    // The processed text of the license (lowercased, punctuation stripped, etc.) used for matching.
    processed_text: Option<String>,
    // A set of file names, any of which unambiguously identify the license.
    file_names: BTreeSet<OsString>,
}

impl TryFrom<&License> for ParsedLicense {
    type Error = crate::Error;

    fn try_from(value: &License) -> Result<Self, Self::Error> {
        Ok(ParsedLicense {
            req: Licensee::parse(value.name)?.into_req(),
            raw_text: value.text.map(str::to_string),
            processed_text: value.text.map(file_classifier::strip_punctuation),
            file_names: value.file_names.iter().map(|f| OsString::from(f)).collect(),
        })
    }
}

// TODO: validation
// -- The processed text can't be a substring of any other processed text
// -- Each file name is globally unique.
// -- Each req is unique.
// -- All licenses must be findable.
// -- License text and file names can't both be none
// -- License exceptions must be parseable
struct Licenses {
    licenses: Vec<ParsedLicense>,
    license_preference: Vec<Licensee>,
    crate_license_special_cases: BTreeMap<&'static str, (Option<&'static str>, &'static str)>,
}
impl Licenses {
    fn new(
        licenses: &'static [License],
        license_preference: &[&str],
        crate_license_special_cases: &'static [CrateLicenseSpecialCase],
    ) -> Result<Licenses, Error> {
        let licenses: Vec<ParsedLicense> =
            licenses.iter().map(|l| ParsedLicense::try_from(l).unwrap()).collect();

        Ok(Licenses { licenses, license_preference: todo!(), crate_license_special_cases: todo!() })
    }
}
