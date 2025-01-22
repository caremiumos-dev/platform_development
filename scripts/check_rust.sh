#!/bin/bash

# Copyright 2025 Google Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Allows the calling of check_rust script in a platform agnostic
# manner. This script relies on it existing in the build/soong/scripts
# directory, since it will use its directory to figure out the location
# of ANDROID_BUILD_TOP.

OS_NAME=$(uname | awk '{print tolower($0)}')
DIRNAME="$(dirname "$0")"
if [ "$OS_NAME" == "darwin" ]; then
    OS_NAME="mac"
fi
machine_name=$(uname -m)
case "$machine_name" in
    x86_64)
        ARCH="x86"
        ;;
    aarch64 | arm64)
        ARCH="arm64"
        ;;
    *)
        ARCH="$machine_name"
        ;;
esac
"${DIRNAME}/../../../out/host/${OS_NAME}-${ARCH}/bin/check_rust" "$@"
