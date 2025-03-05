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
    use crate::LICENSE_DATA;
    use spdx::Licensee;
    use std::path::Path;

    #[test]
    fn test_classify() {
        assert!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSE")).is_none(),
            "Generic license name"
        );
        assert_eq!(
            LICENSE_DATA.classify_file_name(Path::new("UNLICENSE")),
            Some(&Licensee::parse("Unlicense").unwrap()),
            "Unlicense"
        );
        assert_eq!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSE-MIT")),
            Some(&Licensee::parse("MIT").unwrap()),
            "Standard name"
        );
        assert_eq!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSE-APACHE")),
            Some(&Licensee::parse("Apache-2.0").unwrap()),
            "Standard name"
        );
        assert_eq!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSE-apache")),
            Some(&Licensee::parse("Apache-2.0").unwrap()),
            "Case-insensitive"
        );
        assert_eq!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSE-APACHE.md")),
            Some(&Licensee::parse("Apache-2.0").unwrap()),
            ".md extension ignored"
        );
        assert_eq!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSE-0BSD.txt")),
            Some(&Licensee::parse("0BSD").unwrap()),
            ".txt extension ignored"
        );
        assert!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSE-0BSD.jpg")).is_none(),
            "Random extension preserved"
        );
        assert_eq!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSES/MIT")),
            Some(&Licensee::parse("MIT").unwrap()),
            "Subdirectory"
        );
        assert_eq!(
            LICENSE_DATA.classify_file_name(Path::new("LICENSES/Apache-2.0")),
            Some(&Licensee::parse("Apache-2.0").unwrap()),
            "Subdirectory"
        );
    }
}
