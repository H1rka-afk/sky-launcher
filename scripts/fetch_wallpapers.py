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

# Космос — добавляются в конец списка (номера уже выбранных обоев не сдвигаются)
SPACE = [
    ("photo-1462331940025-496dfbfc7564", "Туманность"),
    ("photo-1446776811953-b23d57bd21aa", "Земля из космоса"),
    ("photo-1419242902214-272b3f66ee7a", "Млечный Путь"),
    ("photo-1444703686981-a3abbc4d4fe3", "Звёздное небо"),
    ("photo-1502134249126-9f3755a50d78", "Галактика"),
    ("photo-1451187580459-43490279c0fa", "Огни Земли"),
    ("photo-1543722530-d2c3201371e7", "Космос"),
    ("photo-1465101162946-4377e57745c3", "Ночное небо"),
    ("photo-1454789548928-9efd52dc4031", "Орбита"),
    ("photo-1506318137071-a8e063b4bec0", "Звёзды"),
    ("photo-1534796636912-3b95b3ab5986", "Звёздная пыль"),
    ("photo-1516339901601-2e1b62dc0c45", "Над облаками"),
]
SPACE_WANT = 8

def add_space(meta):
    if any(m.get("group") == "space" for m in meta):
        return False
    if subprocess.call("command -v convert >/dev/null", shell=True) != 0:
        subprocess.check_call("sudo apt-get update -qq && sudo apt-get install -y -qq imagemagick webp >/dev/null", shell=True)
    added = 0
    for pid, name in SPACE:
        if added >= SPACE_WANT: break
        raw = "/tmp/raw_space.jpg"
        try:
            fetch(f"https://images.unsplash.com/{pid}?w=2400&q=88&fm=jpg&fit=max", raw)
            w, h = size(raw)
        except Exception as e:
            print("skip", pid, e); continue
        if w < h * 1.2:
            print("skip (not landscape)", pid, w, h); continue
        n = len(meta) + 1
        rel = f"wallpapers/wp_{n:02d}.webp"
        dst = os.path.join("app/src/main/assets", rel)
        subprocess.check_call(["convert", raw, "-auto-orient", "-resize", "1920x1080^", "-gravity", "center",
                               "-extent", "1920x1080", "-strip", "-quality", "80", dst])
        if not os.path.exists(dst) or os.path.getsize(dst) < 10000:
            print("convert failed", pid); continue
        meta.append({"file": rel, "name": name, "group": "space", "source": f"https://unsplash.com/photos/{pid}"})
        added += 1
        print("ok space", pid, name, w, h, os.path.getsize(dst))
    return added > 0

def main():
    os.makedirs(OUT, exist_ok=True)
    if os.path.exists(META):
        meta = json.load(open(META))
        if len(meta) >= 8 and all(os.path.exists(os.path.join("app/src/main/assets", m["file"])) for m in meta):
            print("Wallpapers already present:", len(meta))
            if add_space(meta):
                json.dump(meta, open(META, "w"), ensure_ascii=False, indent=1)
                print("Saved with space:", len(meta))
            return
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
