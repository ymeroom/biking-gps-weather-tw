"""GPX 錄製檔 -> 精簡路線 JSON（給網站畫路線用）。

原始 GPX 是實騎錄製檔，每 5-6 秒一個點，紅燈等待時會有大量幾乎重疊的點。
這裡用「跟上一個保留點距離 >= MIN_DIST_M 才保留」做簡單抽稀，
把幾千點的錄製檔縮成幾百點、畫出來一樣平滑的路線形狀。

用法：
    python scripts/gpx_to_routes.py <gpx路徑> <輸出json路徑> "<路線顯示名稱>"
"""
import sys
import json
import math
import xml.etree.ElementTree as ET

MIN_DIST_M = 12.0
NS = {"g": "http://www.topografix.com/GPX/1/1"}


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def parse_gpx(path):
    # 只處理自己匯出的 GPX（本機檔案，非網路輸入）；仍擋掉 DOCTYPE 避免萬一有惡意實體。
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    if "<!DOCTYPE" in text or "<!ENTITY" in text:
        raise ValueError("GPX 含 DOCTYPE/ENTITY，拒絕解析")
    root = ET.fromstring(text)
    pts = []
    for trkpt in root.iter():
        tag = trkpt.tag.rsplit("}", 1)[-1]  # 去掉可能存在的 xmlns 命名空間前綴
        if tag == "trkpt":
            pts.append((float(trkpt.get("lat")), float(trkpt.get("lon"))))
    return pts


def decimate(pts, min_dist_m):
    if not pts:
        return []
    kept = [pts[0]]
    for lat, lon in pts[1:]:
        plat, plon = kept[-1]
        if haversine_m(plat, plon, lat, lon) >= min_dist_m:
            kept.append((lat, lon))
    if kept[-1] != pts[-1]:
        kept.append(pts[-1])
    return kept


def total_distance_km(pts):
    d = 0.0
    for (lat1, lon1), (lat2, lon2) in zip(pts, pts[1:]):
        d += haversine_m(lat1, lon1, lat2, lon2)
    return round(d / 1000, 1)


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(1)
    gpx_path, out_path, name = sys.argv[1], sys.argv[2], sys.argv[3]
    raw = parse_gpx(gpx_path)
    kept = decimate(raw, MIN_DIST_M)
    data = {
        "name": name,
        "distanceKm": total_distance_km(kept),
        "points": [[round(lat, 5), round(lon, 5)] for lat, lon in kept],
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    msg = f"{name}: {len(raw)} -> {len(kept)} pts, {data['distanceKm']} km -> {out_path}"
    sys.stdout.buffer.write(msg.encode("utf-8") + b"\n")


if __name__ == "__main__":
    main()
