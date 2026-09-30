#!/usr/bin/env python3
import argparse
import csv
import hashlib
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

REGION_MAP = {
    "서울특별시": "SEOUL", "서울": "SEOUL",
    "부산광역시": "BUSAN", "부산": "BUSAN",
    "대구광역시": "DAEGU", "대구": "DAEGU",
    "인천광역시": "INCHEON", "인천": "INCHEON",
    "광주광역시": "GWANGJU", "광주": "GWANGJU",
    "대전광역시": "DAEJEON", "대전": "DAEJEON",
    "울산광역시": "ULSAN", "울산": "ULSAN",
    "세종특별자치시": "SEJONG", "세종": "SEJONG",
    "경기도": "GYEONGGI", "경기": "GYEONGGI",
    "강원특별자치도": "GANGWON", "강원도": "GANGWON", "강원": "GANGWON",
    "충청북도": "CHUNGBUK", "충북": "CHUNGBUK",
    "충청남도": "CHUNGNAM", "충남": "CHUNGNAM",
    "전북특별자치도": "JEONBUK", "전라북도": "JEONBUK", "전북": "JEONBUK",
    "전라남도": "JEONNAM", "전남": "JEONNAM",
    "경상북도": "GYEONGBUK", "경북": "GYEONGBUK",
    "경상남도": "GYEONGNAM", "경남": "GYEONGNAM",
    "제주특별자치도": "JEJU", "제주도": "JEJU", "제주": "JEJU",
}
FILE_MAP = {
    "SEOUL":"seoul.json","BUSAN":"busan.json","DAEGU":"daegu.json","INCHEON":"incheon.json",
    "GWANGJU":"gwangju.json","DAEJEON":"daejeon.json","ULSAN":"ulsan.json","SEJONG":"sejong.json",
    "GYEONGGI":"gyeonggi.json","GANGWON":"gangwon.json","CHUNGBUK":"chungbuk.json","CHUNGNAM":"chungnam.json",
    "JEONBUK":"jeonbuk.json","JEONNAM":"jeonnam.json","GYEONGBUK":"gyeongbuk.json","GYEONGNAM":"gyeongnam.json","JEJU":"jeju.json"
}

def first(row, *names):
    for name in names:
        value = row.get(name)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None

def number(value):
    if value is None:
        return None
    try:
        return float(str(value).replace(",", "").strip())
    except Exception:
        return None

def integer(value):
    n = number(value)
    return int(n) if n is not None else None

def stable_id(region, management_no, lat, lon, kind):
    raw = f"{region}|{management_no or ''}|{lat:.7f}|{lon:.7f}|{kind}".encode("utf-8")
    value = int.from_bytes(hashlib.sha256(raw).digest()[:8], "big") & ((1 << 63) - 1)
    return value or 1

def camera_type(value, section_type=None, section_length=None):
    # Public-data standard regltSe:
    # 01 speed, 02 signal, 03 traffic-lane violation,
    # 04 illegal parking, 99 other. Multiple values may use '+'.
    text = (value or "").strip().replace(" ", "")
    codes = {token.zfill(2) for token in text.replace("/", "+").split("+") if token}
    labels = text.lower()

    is_speed = "01" in codes or "속도" in labels
    is_signal = "02" in codes or "신호" in labels

    # SafeNavi currently warns only for driving-relevant speed/signal cameras.
    # Exclude traffic-lane, parking and miscellaneous-only cameras.
    if not (is_speed or is_signal):
        return None

    if section_type or (section_length is not None and section_length > 0):
        return "SECTION"
    if is_signal:
        return "SIGNAL_SPEED"
    return "SPEED"

def load_rows(path):
    last_error = None
    for encoding in ("utf-8-sig", "cp949", "euc-kr"):
        try:
            with path.open("r", encoding=encoding, newline="") as f:
                return list(csv.DictReader(f))
        except UnicodeDecodeError as ex:
            last_error = ex
    raise last_error or RuntimeError(f"cannot read {path}")

def main():
    ap = argparse.ArgumentParser(description="Import Korean public-standard enforcement-camera CSV files")
    ap.add_argument("csv", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, default=Path("data/enforcement"))
    args = ap.parse_args()

    grouped = defaultdict(list)
    rejected = 0

    for path in args.csv:
        for row in load_rows(path):
            province = first(row, "시도명", "시·도명", "시도")
            region = REGION_MAP.get(province or "")
            lat = number(first(row, "위도", "latitude", "Latitude"))
            lon = number(first(row, "경도", "longitude", "Longitude"))
            if not region or lat is None or lon is None or not (32.0 <= lat <= 39.5 and 123.0 <= lon <= 132.5):
                rejected += 1
                continue

            section_type = first(row, "단속구간위치구분", "단속구간구분")
            section_length = number(first(row, "과속단속구간길이", "단속구간길이", "구간길이"))
            if section_length is not None and section_length <= 0:
                section_length = None

            kind = camera_type(
                first(row, "단속구분", "단속유형", "카메라구분"),
                section_type,
                section_length,
            )
            if kind is None:
                rejected += 1
                continue

            speed = integer(first(row, "제한속도", "제한속도(km/h)", "속도제한"))
            # The public-data standard uses 0 when no speed limit is specified.
            if speed == 0:
                speed = None
            if speed is not None and not (10 <= speed <= 130):
                rejected += 1
                continue

            management_no = first(row, "카메라관리번호", "무인교통단속카메라관리번호", "관리번호")
            point = {
                "id": stable_id(region, management_no, lat, lon, kind),
                "latitude": lat,
                "longitude": lon,
                "type": kind,
                "speedLimit": speed,
                "roadName": first(row, "도로노선명", "도로명", "도로명주소"),
                "locationName": first(row, "설치장소", "소재지도로명주소", "소재지지번주소"),
                "direction": None,
                "sectionType": section_type,
                "sectionLength": section_length,
                "dataDate": first(row, "데이터기준일자", "기준일자"),
                "source": "Korean Public Data Standard",
                "sourceKind": "ENFORCEMENT_CAMERA",
            }
            grouped[region].append(point)

    args.out.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    datasets = []

    for region, points in sorted(grouped.items()):
        unique = {p["id"]: p for p in points}
        points = sorted(unique.values(), key=lambda p: (p["latitude"], p["longitude"], p["id"]))
        if not points:
            continue

        canonical = json.dumps(points, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        version = hashlib.sha256(canonical).hexdigest()[:12]
        filename = FILE_MAP[region]
        path = args.out / filename
        doc = {
            "schema": 2,
            "region": region,
            "version": version,
            "updatedAt": now,
            "source": "Korean Public Data Standard",
            "sourceKind": "ENFORCEMENT_CAMERA",
            "points": points,
        }
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        raw = path.read_bytes()
        datasets.append({
            "region": region,
            "version": version,
            "path": f"data/enforcement/{filename}",
            "source": "Korean Public Data Standard",
            "sourceKind": "ENFORCEMENT_CAMERA",
            "count": len(points),
            "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
        })

    if not datasets:
        raise SystemExit("No valid enforcement-camera rows were imported; existing manifest was not replaced.")

    manifest = {
        "schema": 2,
        "updatedAt": now,
        "source": "Korean Public Data Standard",
        "sourceKind": "ENFORCEMENT_CAMERA",
        "datasets": datasets,
    }
    (args.out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"regions": len(datasets), "points": sum(x["count"] for x in datasets), "rejected": rejected}, ensure_ascii=False))

if __name__ == "__main__":
    main()
