#!/usr/bin/env bash
# Downloads the offline Scottish navigation voice and its engine. They are too big for git
# (about 80 MB), so CI and local builds fetch them here. Checksums pin the exact files.
#   app/libs/sherpa-onnx-<ver>.aar      speech engine (sherpa-onnx, Apache 2.0)
#   app/src/main/assets/voice/          Piper en_GB VCTK model (CC BY 4.0) + English phoneme data
set -euo pipefail
cd "$(dirname "$0")/.."

AAR_VERSION=1.13.7
AAR_SHA256=c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8
MODEL_SHA256=abafd35bdab0a72a3c6b947228ae3cccdf3624db83313c77d1abb5cbc75e1f64

aar="app/libs/sherpa-onnx-$AAR_VERSION.aar"
if [ ! -f "$aar" ]; then
  mkdir -p app/libs
  curl -fsSL --retry 3 -o "$aar.part" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$AAR_VERSION/sherpa-onnx-$AAR_VERSION.aar"
  mv "$aar.part" "$aar"
fi
echo "$AAR_SHA256  $aar" | sha256sum -c -

dest=app/src/main/assets/voice
if [ ! -f "$dest/model.onnx" ]; then
  tmp=$(mktemp -d)
  curl -fsSL --retry 3 -o "$tmp/model.tar.bz2" \
    https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-vctk-medium.tar.bz2
  echo "$MODEL_SHA256  $tmp/model.tar.bz2" | sha256sum -c -
  tar xjf "$tmp/model.tar.bz2" -C "$tmp"
  src="$tmp/vits-piper-en_GB-vctk-medium"
  # Only English is spoken: drop the other languages' dictionaries (saves about 17 MB).
  find "$src/espeak-ng-data" -maxdepth 1 -name '*_dict' ! -name 'en_dict' -delete
  rm -rf "$dest"
  mkdir -p "$dest"
  mv "$src/en_GB-vctk-medium.onnx" "$dest/model.onnx"
  mv "$src/tokens.txt" "$dest/tokens.txt"
  mv "$src/espeak-ng-data" "$dest/espeak-ng-data"
  cp "$src/MODEL_CARD" "$dest/MODEL_CARD"
  rm -rf "$tmp"
fi
echo "Voice ready: $(du -sh "$dest" | cut -f1) model, engine $aar"
