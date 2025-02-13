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

#[cfg(feature = "fuzzy_content_match")]
use itertools::Itertools;
use regex::Regex;
use spdx::{LicenseReq, Licensee};
use std::sync::LazyLock;
#[cfg(feature = "fuzzy_content_match")]
use textdistance::str::ratcliff_obershelp;

static REGEXES: LazyLock<Vec<(Regex, &str)>> = LazyLock::new(|| {
    vec![
        (Regex::new(r#"^zlib License"#).unwrap(), ""),  // Optional zlib license header
        (Regex::new(r#"^MIT License"#).unwrap(), ""),  // Optional MIT license header
        (Regex::new(r#"^ISC License:"#).unwrap(), ""),  // Optional ISC license header
        (Regex::new(r#"^OpenSSL License"#).unwrap(), ""),  // Optional OpenSSL license header

        (Regex::new(r#"(?m)^[ \t]*(//|/\*)[ \t]*"#).unwrap(), ""),  // Comment at start of line
        (Regex::new(r#"(?m)^[ \t]*\*[ \t]+"#).unwrap(), ""),  // * at start of line, which could be part of a comment, or a bullet item.

        (Regex::new(r#"(?mi)^[ \t]*copyright +((©|\( *c *\)) +)?(\d\d\d\d|[\[<\{](yyyy|year)[\]>\}]|year).*$"#).unwrap(), ""),  // Generic copyright declaration.
        (Regex::new(r#"(?mi)^[ \t]*Copyright \(c\).*$"#).unwrap(), ""),  // Copyright declaration without year
        (Regex::new(r#"(?mi)^[ \t]*All rights reserved\.[ \t]*$"#).unwrap(), ""),  // "All rights reserved." alone on a line

        (Regex::new(r#"(?is)APPENDIX: How to apply the Apache License to your work\..*?limitations under the License\."#).unwrap(), ""),  // Apache-2.0 license appendix. Sometimes included, sometimes not.

        (Regex::new(r#"(?is)UNICODE, INC. LICENSE AGREEMENT - DATA FILES AND SOFTWARE.*?COPYRIGHT AND PERMISSION NOTICE"#).unwrap(), ""),  // Remove Unicode preamble

        (Regex::new(r#"(?is)University of Illinois/NCSA.*?Permission is hereby granted, free of charge, to any person obtaining a copy of"#).unwrap(), "Permission is hereby granted, free of charge, to any person obtaining a copy of"),  // Remove NCSA preamble
        (Regex::new(r#"(?is)Neither the names of the LLVM Team, University of Illinois at\s+Urbana-Champaign, nor the names of its contributors may be used"#).unwrap(), "Neither the names of <Name of Development Group, Name of Institution>, nor the names of its contributors may be used"),  // Normalize NCSA

        // Normalize variants of BSD licenses
        (Regex::new(r#"(?is)Neither the name of .*? nor the names of its"#).unwrap(), "Neither the name of the copyright holder nor the names of its"),  
        (Regex::new(r#"(?is)OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL"#).unwrap(), "HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL"),
        (Regex::new(r#"(?is)IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE"#).unwrap(), "IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE"),

        // Genericize ISC license
        (Regex::new(r#"(?is)THE SOFTWARE IS PROVIDED "AS IS" AND (ISC DISCLAIMS|THE AUTHORS DISCLAIM) ALL WARRANTIES"#).unwrap(), r#"THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES"#),  
        (Regex::new(r#"(?is)IN NO EVENT SHALL (THE AUTHORS|ISC) BE LIABLE FOR"#).unwrap(), r#"IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR"#),

        (Regex::new(r#"(?is)Mozilla Public License, v\.\s+2\.0"#).unwrap(), r#"Mozilla Public License, v. 2.0"#),  // Special case for MPL-2.0 so we don't treat 2.0 at start of line as a section number.

        (Regex::new(r#"(?m)^[ \t]*\d+\.[ \t]+"#).unwrap(), ""),  // 1. 2. 3. etc. at start of line.
        (Regex::new(r#"(?m)^[ \t]*\d+\.\d+\.[ \t]+"#).unwrap(), ""),  // 1.1. 1.2. 2.1. etc. at start of line.
        (Regex::new(r#"(?mi)^[ \t]*[a-f]\.[ \t]+"#).unwrap(), ""), // a. b. c. etc. at start of line.
        (Regex::new(r#"(?mi)^[ \t]*\([a-f]\)[ \t]+"#).unwrap(), ""), // (a) (b) (c) etc. at start of line.

        (Regex::new(r#"(?mi)https://"#).unwrap(), "http://"),  // Treat http and https urls the same.

        (Regex::new(r#"(?i)orother"#).unwrap(), "or other"),  // Typo in ml-rs Apache license files.
    ]
});

fn strip_punctuation(text: &str) -> String {
    let mut text = text.to_string();
    for (re, replacement) in REGEXES.iter() {
        text = re.replace_all(&text, *replacement).to_string();
    }
    let lowercase = text.to_lowercase();
    let mut processed = String::with_capacity(lowercase.len());
    for c in lowercase.chars() {
        if c.is_alphanumeric() || c == '.' || c == ':' || c == ';' {
            processed.push(c)
        } else if !processed.ends_with(' ') {
            processed.push(' ')
        }
    }
    processed.trim().to_string()
}

pub(crate) fn classify_license_file_contents(contents: &str) -> Vec<LicenseReq> {
    let contents = strip_punctuation(contents);

    // Exact match
    let mut matches = Vec::new();
    for (req, required_text, required_exception) in LICENSE_CONTENT_CLASSIFICATION.iter() {
        if req.license.id().unwrap().name == "OpenSSL" {
            // println!("{contents}")
        }
        if contents.contains(required_text)
            && required_exception
                .as_ref()
                .is_none_or(|exception_text| contents.contains(exception_text))
        {
            matches.push(req.clone());
        }
    }

    // Filter out matches where we also have a more specific match (i.e. "Apache-2.0 WITH LLVM-exception" supersedes "Apache-2.0")
    let raw_matches = matches.clone();
    matches.retain(|req| match req.exception {
        Some(_) => true,
        None => !raw_matches.iter().any(|other_req| {
            println!(
                "req: {:?}\nother_req:{:?}\nequals: {}",
                req,
                other_req,
                req.license == other_req.license
            );
            req.license == other_req.license && other_req.exception.is_some()
        }),
    });

    if !matches.is_empty() {
        return matches;
    }

    // Fuzzy match. This is expensive, so start with licenses that are closest in length to the file,
    // and only return a single match at most.
    #[cfg(feature = "fuzzy_content_match")]
    for (req, required_text, required_exception) in
        LICENSE_CONTENT_CLASSIFICATION.iter().sorted_by(|a, b| {
            let mut ra = a.1.len() as f32 / contents.len() as f32;
            let mut rb = b.1.len() as f32 / contents.len() as f32;
            if ra > 1.0 {
                ra = 1.0 / ra;
            }
            if rb > 1.0 {
                rb = 1.0 / rb;
            }
            rb.partial_cmp(&ra).unwrap()
        })
    {
        // let similarity = ratcliff_obershelp(contents.as_str(), required_text);
        // if similarity > 0.95 {
        //     matches.push(req.clone());
        //     break;
        // }
    }

    matches
}

static LICENSE_CONTENT_CLASSIFICATION: LazyLock<Vec<(LicenseReq, String, Option<String>)>> =
    LazyLock::new(|| {
        vec![
            "MIT",
            "Apache-2.0 WITH LLVM-exception",
            "Apache-2.0",
            "ISC",
            "MPL-2.0",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "Unicode-3.0",
            "Unicode-DFS-2016",
            "Unlicense",
            "Zlib",
            "OpenSSL",
            "NCSA",
            "0BSD",
            "CC0-1.0",
        ]
        .into_iter()
        .map(|req| {
            if req == "Apache-2.0 WITH LLVM-exception" {
                println!("{:?}", Licensee::parse(req).unwrap());
                println!("{:?}", Licensee::parse(req).unwrap().into_req());
            }
            let req = Licensee::parse(req).unwrap().into_req();
            let tokens = strip_punctuation(req.license.id().unwrap().text());
            if req.license.id().unwrap().name == "Apache-2.0" {
                // println!("{tokens}")
            }
            assert!(!tokens.is_empty());
            let exception = req.exception.map(|e| strip_punctuation(e.text()));
            (req, tokens, exception)
        })
        .collect()
    });

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_strip_punctuation() {
        assert_eq!(strip_punctuation("FOO BAR"), "foo bar", "Converted to lowercase");
        assert_eq!(strip_punctuation("foo, bar"), "foo bar", "Punctuation removed");
        assert_eq!(strip_punctuation("foo. bar"), "foo. bar", "Periods preserved");
        assert_eq!(
            strip_punctuation(" foo bar "),
            "foo bar",
            "Leading and trailing whitespace stripped"
        );
        assert_eq!(
            strip_punctuation(" foo\n\n\n\nbar "),
            "foo bar",
            "Multiple whitespace replaced with single space"
        );
    }

    #[test]
    fn test_classify() {
        assert!(classify_license_file_contents("foo").is_empty());
        assert_eq!(
            classify_license_file_contents(include_str!("testdata/LICENSE-MIT-aarch64-paging.txt")),
            vec![Licensee::parse("MIT").unwrap().into_req()]
        );
    }

    #[cfg(feature = "fuzzy_content_match")]
    #[test]
    fn test_classify_fuzzy() {
        assert_eq!(
            classify_license_file_contents(include_str!("testdata/BSD-3-Clause-bindgen.txt")),
            vec![Licensee::parse("BSD-3-Clause").unwrap().into_req()]
        );
    }

    #[test]
    fn concatenated_licenses() {
        assert_eq!(
            classify_license_file_contents(
                format!(
                    "{}\n\n{}",
                    Licensee::parse("Apache-2.0").unwrap().into_req().license.id().unwrap().text(),
                    Licensee::parse("MIT").unwrap().into_req().license.id().unwrap().text(),
                )
                .as_str()
            ),
            vec![
                Licensee::parse("MIT").unwrap().into_req(),
                Licensee::parse("Apache-2.0").unwrap().into_req()
            ]
        );
    }
}
