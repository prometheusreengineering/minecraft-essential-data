#!/usr/bin/env bash
# Downloads Essential's Fabric 1.20.1 build of the given version (default: the latest) to lib/essential.jar, the jar
# the recorder compiles and tests against. Not redistributed here.
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:-$(scripts/latest-essential.sh)}"
meta="$(curl -fsSL --retry 3 "https://api.essential.gg/mods/v1/essential:essential/versions/$version/platforms/fabric_1-20-1/download")"
mkdir -p lib
curl -fsSL --retry 3 "$(jq -r .url <<<"$meta")" -o lib/essential.jar
echo "$(jq -r .checksum <<<"$meta")  lib/essential.jar" | md5sum -c -
echo "Essential $version (fabric_1-20-1) -> lib/essential.jar"
