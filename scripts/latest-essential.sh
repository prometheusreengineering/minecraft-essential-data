#!/usr/bin/env bash
# Prints the newest release of Essential on Modrinth (e.g. 1.5.2). Both this repo and the essential repo test against
# it, written into their Verity profiles as an exact version, so targets without that build are skipped.
set -euo pipefail
version="$(curl -fsSL --retry 3 'https://api.modrinth.com/v2/project/essential/version' \
  | jq -r '[.[] | select(.version_type == "release")] | sort_by(.date_published) | last | .version_number')"
[[ -n "$version" && "$version" != null ]] || { echo "no Essential release on Modrinth" >&2; exit 1; }
echo "$version"
