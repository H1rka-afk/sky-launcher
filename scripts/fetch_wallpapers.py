#!/usr/bin/env python3
"""Скачивает качественные фото природы для обоев Sky OS (Unsplash License)
и складывает их в assets/wallpapers + список в assets/wallpapers.json."""
import json, os, subprocess, sys, urllib.request

OUT = "app/src/main/assets/wallpapers"
META = "app/src/main/assets/wallpapers.json"
UA = "SkyOS-wallpapers/1.0 (github.com/H1rka-afk/sky-launcher)"
WANT = 14

# (id на images.unsplash.com, название)
CANDIDATES = [
    ("photo-1506744038136-46273834b3fb", "Горное озеро"),
    ("photo-1501785888041-af3ef285b470", "Озеро Брайес"),
    ("photo-1470071459604-3b5ec3a7fe05", "Туман в горах"),
    ("photo-1441974231531-c6227db76b6e", "Солнечный лес"),
    ("photo-1469474968028-56623f02e42e", "Рассвет в горах"),
    ("photo-1472214103451-9374bd1c798e", "Зелёные холмы"),
    ("photo-1447752875215-b2761acb3c5d", "Лесная тропа"),
    ("photo-1433086966358-54859d0ed716", "Водопад"),
    ("photo-1507525428034-b723cf961d3e", "Тропический пляж"),
    ("photo-1519681393784-d120267933ba", "Звёздная ночь"),
    ("photo-1464822759023-fed622ff2c3b", "Вершина"),
    ("photo-1483347756197-71ef80e95f73", "Северное сияние"),
    ("photo-1426604966848-d7adac402bff", "Долина"),
    ("photo-1418065460487-3e41a6c84dc5", "Туманный лес"),
    ("photo-1500534314209-a25ddb2bd429", "Луг"),
    ("photo-1475924156734-496f6cac6ec1", "Закат над полем"),
    ("photo-1505144808419-1957a94ca61e", "Океан"),
    ("photo-1439066615861-d1af74d74000", "Озеро"),
    ("photo-1454496522488-7a8e488e8606", "Снежные горы"),
    ("photo-1470770841072-f978cf4d019e", "Домик у озера"),
]

def fetch(url, path):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r, open(path, "wb") as f:
        f.write(r.read())

def size(path):
    out = subprocess.check_output(["identify", "-format", "%w %h", path + "[0]"]).decode().split()
    return int(out[0]), int(out[1])

def main():
    os.makedirs(OUT, exist_ok=True)
    if os.path.exists(META):
        meta = json.load(open(META))
        if len(meta) >= 8 and all(os.path.exists(os.path.join("app/src/main/assets", m["file"])) for m in meta):
            print("Wallpapers already present:", len(meta)); return
    if subprocess.call("command -v convert >/dev/null", shell=True) != 0:
        subprocess.check_call("sudo apt-get update -qq && sudo apt-get install -y -qq imagemagick >/dev/null", shell=True)
    meta = []
    for pid, name in CANDIDATES:
        if len(meta) >= WANT: break
        raw = "/tmp/raw.jpg"
        try:
            fetch(f"https://images.unsplash.com/{pid}?w=2400&q=88&fm=jpg&fit=max", raw)
            w, h = size(raw)
        except Exception as e:
            print("skip", pid, e); continue
        if w < h * 1.25:
            print("skip (not landscape)", pid, w, h); continue
        n = len(meta) + 1
        rel = f"wallpapers/wp_{n:02d}.jpg"
        subprocess.check_call(["convert", raw, "-auto-orient", "-resize", "1920x1080^", "-gravity", "center",
                               "-extent", "1920x1080", "-strip", "-interlace", "Plane", "-quality", "84",
                               os.path.join("app/src/main/assets", rel)])
        meta.append({"file": rel, "name": name, "source": f"https://unsplash.com/photos/{pid}"})
        print("ok", pid, name, w, h)
    if len(meta) < 6:
        print("Too few wallpapers downloaded:", len(meta)); sys.exit(1)
    json.dump(meta, open(META, "w"), ensure_ascii=False, indent=1)
    print("Saved", len(meta))

main()
