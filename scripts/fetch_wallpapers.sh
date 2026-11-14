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
  UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
  page=$(curl -fsSL -A "$UA" "https://unsplash.com/photos/$id" || true)
  img=$(echo "$page" | grep -o 'https://images.unsplash.com/photo-[A-Za-z0-9_-]*' | head -1)
  if [ -z "$img" ]; then
    img=$(echo "$page" | grep -o 'https://plus.unsplash.com/premium_photo-[A-Za-z0-9_-]*' | head -1)
  fi
  if [ -z "$img" ]; then echo "No image URL for $id"; exit 1; fi
  echo "  -> $img"
  curl -fsSL -A "$UA" "$img?w=2400&q=90&fm=jpg" -o /tmp/raw.jpg
  convert /tmp/raw.jpg -auto-orient -resize "1920x1080^" -gravity center -extent 1920x1080 -strip -interlace Plane -quality 84 "$f"
done < scripts/wallpapers.txt
ls -la "$DIR"
