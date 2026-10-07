#!/usr/bin/env bash
# Bundle the exact upstream native build scripts and their pinned source trees.
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_root="$project_root/build/native-source"
upstream="$source_root/libmpv-android-1.0.0"
archive="$project_root/build/libmpv-android-1.0.0-source.tar.gz"
mkdir -p "$source_root"
if [[ -e "$upstream" ]]; then
  echo 'Native source staging already exists; use a clean staging directory.' >&2
  exit 1
fi
git -c http.version=HTTP/1.1 clone --depth 1 --branch v1.0.0 https://github.com/jarnedemeulemeester/libmpv-android.git "$upstream"
# Upstream pins all dependency versions. Only replace the Lua transport with HTTPS.
sed -i 's|http://www.lua.org/ftp/|https://www.lua.org/ftp/|g' "$upstream/buildscripts/include/download-deps.sh"
(
  cd "$upstream/buildscripts"
  GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=http.version GIT_CONFIG_VALUE_0=HTTP/1.1 bash include/download-deps.sh
)
# Keep shallow Git metadata: upstream patch.sh uses git reset/checkout for its patches.
tar -czf "$archive" -C "$source_root" libmpv-android-1.0.0
printf 'Native source archive prepared: %s\n' "$archive"
