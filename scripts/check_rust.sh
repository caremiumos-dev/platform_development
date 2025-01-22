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
# manner. This script relies on it existing in the development/scripts
# directory, since it will use its directory to figure out the location
# of ANDROID_BUILD_TOP.

dir_name="$(dirname "$0")"

case $(uname -s) in
    Darwin)
        host_arch=darwin-x86
        ;;
    Linux)
        host_arch=linux-x86
        ;;
    *)
        >&2 echo Unknown host $(uname -s)
        exit 1
        ;;
esac


"${dir_name}/../../out/host/${host_arch}/bin/check_rust" "$@"
