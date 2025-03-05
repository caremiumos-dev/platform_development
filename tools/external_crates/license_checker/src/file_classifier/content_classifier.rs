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

#[cfg(test)]
mod tests {
    use std::collections::BTreeSet;

    use spdx::Licensee;

    use crate::LICENSE_DATA;

    #[test]
    fn test_classify() {
        assert!(LICENSE_DATA.classify_file_contents("foo").is_empty());
        assert_eq!(
            LICENSE_DATA
                .classify_file_contents(include_str!("testdata/LICENSE-MIT-aarch64-paging.txt")),
            BTreeSet::from([Licensee::parse("MIT").unwrap()])
        );
    }

    #[test]
    fn test_classify_fuzzy() {
        assert!(LICENSE_DATA
            .classify_file_contents(include_str!("testdata/BSD-3-Clause-bindgen.txt"))
            .is_empty());
        assert_eq!(
            LICENSE_DATA
                .classify_file_contents_fuzzy(include_str!("testdata/BSD-3-Clause-bindgen.txt")),
            Some(Licensee::parse("BSD-3-Clause").unwrap())
        );
    }

    #[test]
    fn concatenated_licenses() {
        assert_eq!(
            LICENSE_DATA.classify_file_contents(
                format!(
                    "{}\n\n{}",
                    include_str!("licenses/Apache-2.0.txt"),
                    include_str!("licenses/MIT.txt")
                )
                .as_str()
            ),
            BTreeSet::from([
                Licensee::parse("MIT").unwrap(),
                Licensee::parse("Apache-2.0").unwrap()
            ])
        );
    }
}
