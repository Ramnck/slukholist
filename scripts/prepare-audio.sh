#!/usr/bin/env bash
# Fetch pinned native runtime and Russian voice. Run from any directory.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fetch() {
  curl --fail --location --retry 3 --output "$tmp/$1" "$2"
  echo "$3  $tmp/$1" | shasum -a 256 -c -
}
base=https://github.com/k2-fsa/sherpa-onnx/releases/download
fetch runtime.aar "$base/v1.12.20/sherpa-onnx-1.12.20.aar" e3905b1258b577d8d374db798618602ceaaff5805cbf9b356ab25267cd98144d
fetch voice.tar.bz2 "$base/tts-models/vits-piper-ru_RU-dmitri-medium.tar.bz2" c86d0803737de13d441923ff3b3f309482fab8d7af3ec85949942809eb9a3660
mkdir -p app/libs app/src/main/assets/piper
tar -xjf "$tmp/voice.tar.bz2" --strip-components 1 -C app/src/main/assets/piper
mv "$tmp/runtime.aar" app/libs/sherpa-onnx.aar
