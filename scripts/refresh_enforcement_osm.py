#!/usr/bin/env python3
import hashlib
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BASE = Path(__file__).resolve().parents[1] / "data" / "enforcement"
OVERPASS = "https://overpass-api.de/api/interpreter"

REGIONS = {
    "SEOUL": ("서울", "KR-11", "seoul.json"),
    "BUSAN": ("부산", "KR-26", "busan.json"),
    "DAEGU": ("대구", "KR-27", "daegu.json"),
    "INCHEON": ("인천", "KR-28", "incheon.json"),
    "GWANGJU": ("광주", "KR-29", "gwangju.json"),
    "DAEJEON": ("대전", "KR-30", "daejeon.json"),
    "ULSAN": ("울산", "KR-31", "ulsan.json"),
    "SEJONG": ("세종", "KR-50", "sejong.json"),
    "GYEONGGI": ("경기", "KR-41", "gyeonggi.json"),
    "GANGWON": ("강원", "KR-42", "gangwon.json"),
    "CHUNGBUK": ("충북", "KR-43", "chungbuk.json"),
    "CHUNGNAM": ("충남", "KR-44", "chungnam.json"),
    "JEONBUK": ("전북", "KR-45", "jeonbuk.json"),
    "JEONNAM": ("전남", "KR-46", "jeonnam.json"),
    "GYEONGBUK": ("경북", "KR-47", "gyeongbuk.json"),
    "GYEONGNAM": ("경남", "KR-48", "gyeongnam.json"),
    "JEJU": ("제주", "KR-49", "jeju.json"),
}

def fetch_overpass(iso_code):
    query = f"""
[out:json][timeout:150];
area["ISO3166-2"="{iso_code}"]["boundary"="administrative"]->.a;
(
  node["highway"="speed_camera"](area.a);
  node["enforcement"="maxspeed"](area.a);
  node["enforcement"="traffic_signals"](area.a);
);
out tags;
"""
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(
        OVERPASS,
        data=data,
        headers={"User-Agent": "SafeNavi-road-safety-refresh/1.0"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read().decode("utf-8"))

def parse_speed(value):
    if not value:
        return None
    value = str(value).lower().replace("km/h", "").strip()
    if value.isdigit():
        return int(value)
    for token in value.replace(";", " ").split():
        if token.isdigit():
            return int(token)
    return None

def parse_direction(value):
    if value is None:
        return None
    try:
        direction = float(str(value).strip())
        if 0.0 <= direction < 360.0:
            return direction
    except Exception:
        pass
    return None

def normalize(region, raw):
    out = []
    seen = set()
    today = datetime.now(timezone.utc).date().isoformat()

    for e in raw.get("elements", []):
        if e.get("type") != "node":
            continue

        tags = e.get("tags") or {}
        is_speed_camera = tags.get("highway") == "speed_camera"
        enforcement = (tags.get("enforcement") or "").lower()
        if not is_speed_camera and enforcement not in {"maxspeed", "traffic_signals"}:
            continue

        lat = e.get("lat")
        lon = e.get("lon")
        if lat is None or lon is None:
            continue

        ptype = "SIGNAL_SPEED" if enforcement == "traffic_signals" else "SPEED"
        limit = parse_speed(tags.get("maxspeed"))
        direction = parse_direction(tags.get("direction"))

        key = (round(float(lat), 6), round(float(lon), 6), ptype)
        if key in seen:
            continue
        seen.add(key)

        out.append({
            "id": int(e["id"]),
            "latitude": float(lat),
            "longitude": float(lon),
            "type": ptype,
            "speedLimit": limit,
            "roadName": tags.get("name"),
            "locationName": tags.get("description") or tags.get("name") or tags.get("ref"),
            "direction": direction,
            "sectionType": None,
            "sectionLength": None,
            "dataDate": today,
            "source": "OpenStreetMap",
            "sourceId": f"node/{e['id']}",
            "sourceKind": "ENFORCEMENT_CAMERA",
        })

    out.sort(key=lambda p: (p["latitude"], p["longitude"], p["id"]))
    return out

def load_json(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default

def main():
    BASE.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    manifest_path = BASE / "manifest.json"
    manifest = load_json(manifest_path, {"schema": 1, "datasets": []})

    old_versions = {}
    for item in manifest.get("datasets", []):
        try:
            old_versions[item.get("region")] = int(str(item.get("version", "1")).split(".")[0])
        except Exception:
            old_versions[item.get("region")] = 1

    datasets = []
    summary = {}

    for idx, (code, (name, iso, filename)) in enumerate(REGIONS.items()):
        path = BASE / filename
        old = load_json(path, {"points": []})
        version = old_versions.get(code, 1)

        try:
            raw = fetch_overpass(iso)
            points = normalize(code, raw)
            old_points = old.get("points", [])
            if not points:
                raise RuntimeError("empty enforcement-camera result; preserving installed dataset")
            changed = points != old_points
            if changed:
                version += 1

            doc = {
                "schema": 2,
                "region": code,
                "regionName": name,
                "version": str(version),
                "updatedAt": now,
                "source": "OpenStreetMap",
                "sourceLicense": "ODbL",
                "sourceKind": "ENFORCEMENT_CAMERA",
                "sourceNote": "공개된 무인 단속카메라 태그 기반 보조 데이터입니다. 현장 표지와 실제 제한속도가 우선입니다.",
                "points": points,
            }
            path.write_text(
                json.dumps(doc, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            summary[code] = {"count": len(points), "changed": changed}
        except Exception as ex:
            summary[code] = {
                "count": len(old.get("points", [])),
                "changed": False,
                "error": str(ex),
            }

        if path.exists():
            file_bytes = path.read_bytes()
            file_doc = load_json(path, {"points": []})
            file_points = file_doc.get("points", [])
            valid_camera_dataset = (
                file_doc.get("schema") == 2
                and file_doc.get("region") == code
                and file_doc.get("sourceKind") == "ENFORCEMENT_CAMERA"
                and bool(file_points)
            )
            if valid_camera_dataset:
                datasets.append({
                    "region": code,
                    "version": str(file_doc.get("version", version)),
                    "path": f"data/enforcement/{filename}",
                    "source": "OpenStreetMap",
                    "sourceKind": "ENFORCEMENT_CAMERA",
                    "count": len(file_points),
                    "bytes": len(file_bytes),
                    "sha256": hashlib.sha256(file_bytes).hexdigest(),
                })

        if idx < len(REGIONS) - 1:
            time.sleep(2)

    manifest_out = {
        "schema": 2,
        "updatedAt": now,
        "source": "OpenStreetMap",
        "sourceLicense": "ODbL",
        "sourceKind": "ENFORCEMENT_CAMERA",
        "datasets": datasets,
    }
    manifest_path.write_text(
        json.dumps(manifest_out, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
