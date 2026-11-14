#!/usr/bin/env bash
# Скачивает фото природы с Unsplash (Unsplash License) и готовит их как обои 1920×1080.
set -euo pipefail
DIR=app/src/main/res/drawable-nodpi
mkdir -p "$DIR"
command -v convert >/dev/null || (sudo apt-get update -qq && sudo apt-get install -y -qq imagemagick >/dev/null)
i=0
while read -r id name; do
  [ -z "$id" ] && continue
  i=$((i+1))
  f="$DIR/$(printf 'wp_%02d.jpg' "$i")"
  if [ -s "$f" ]; then continue; fi
  echo "Downloading $id ($name) -> $f"
  curl -fsSL -A "Mozilla/5.0" "https://unsplash.com/photos/$id/download?force=true&w=2400" -o /tmp/raw.jpg
  convert /tmp/raw.jpg -auto-orient -resize "1920x1080^" -gravity center -extent 1920x1080 -strip -interlace Plane -quality 84 "$f"
done < scripts/wallpapers.txt
ls -la "$DIR"
