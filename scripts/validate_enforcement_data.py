#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path

REGIONS = {
    "SEOUL","BUSAN","DAEGU","INCHEON","GWANGJU","DAEJEON","ULSAN","SEJONG",
    "GYEONGGI","GANGWON","CHUNGBUK","CHUNGNAM","JEONBUK","JEONNAM",
    "GYEONGBUK","GYEONGNAM","JEJU"
}
TYPES={"SPEED","SIGNAL_SPEED","SECTION"}

def fail(msg):
    raise SystemExit("VALIDATION FAILED: "+msg)

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--base",type=Path,default=Path("data/enforcement"))
    args=ap.parse_args()

    manifest_path=args.base/"manifest.json"
    if not manifest_path.exists():
        fail("manifest.json missing")

    root=json.loads(manifest_path.read_text(encoding="utf-8"))
    if root.get("schema")!=2:
        fail("manifest schema must be 2")
    if root.get("sourceKind")!="ENFORCEMENT_CAMERA":
        fail("manifest sourceKind must be ENFORCEMENT_CAMERA")

    datasets=root.get("datasets")
    if not isinstance(datasets,list):
        fail("manifest datasets must be a list")

    seen_regions=set()
    total_points=0

    for item in datasets:
        region=str(item.get("region","")).upper()
        if region not in REGIONS:
            fail(f"unknown region: {region}")
        if region in seen_regions:
            fail(f"duplicate region: {region}")
        seen_regions.add(region)

        path_text=str(item.get("path",""))
        if not path_text.startswith("data/enforcement/") or ".." in path_text or not path_text.endswith(".json"):
            fail(f"{region}: invalid path")
        path=Path(path_text)
        if not path.exists():
            fail(f"{region}: file missing: {path_text}")

        raw=path.read_bytes()
        expected_bytes=item.get("bytes")
        expected_sha=str(item.get("sha256","")).lower()
        expected_count=item.get("count")

        if not isinstance(expected_bytes,int) or expected_bytes<=0 or len(raw)!=expected_bytes:
            fail(f"{region}: byte size mismatch")
        actual_sha=hashlib.sha256(raw).hexdigest()
        if len(expected_sha)!=64 or actual_sha!=expected_sha:
            fail(f"{region}: sha256 mismatch")

        doc=json.loads(raw.decode("utf-8"))
        if doc.get("schema")!=2:
            fail(f"{region}: schema must be 2")
        if doc.get("sourceKind")!="ENFORCEMENT_CAMERA":
            fail(f"{region}: sourceKind mismatch")
        if str(doc.get("region","")).upper()!=region:
            fail(f"{region}: dataset region mismatch")
        if str(doc.get("version",""))!=str(item.get("version","")):
            fail(f"{region}: version mismatch")

        points=doc.get("points")
        if not isinstance(points,list) or len(points)==0:
            fail(f"{region}: empty points")
        if not isinstance(expected_count,int) or expected_count!=len(points):
            fail(f"{region}: count mismatch")

        ids=set()
        for p in points:
            pid=p.get("id")
            if not isinstance(pid,int) or pid<=0:
                fail(f"{region}: invalid id")
            if pid in ids:
                fail(f"{region}: duplicate id {pid}")
            ids.add(pid)

            lat=p.get("latitude")
            lon=p.get("longitude")
            if not isinstance(lat,(int,float)) or not 32.0<=float(lat)<=39.5:
                fail(f"{region}: invalid latitude")
            if not isinstance(lon,(int,float)) or not 123.0<=float(lon)<=132.5:
                fail(f"{region}: invalid longitude")

            ptype=str(p.get("type","")).upper()
            if ptype not in TYPES:
                fail(f"{region}: invalid type {ptype}")

            speed=p.get("speedLimit")
            if speed is not None and (not isinstance(speed,int) or speed<10 or speed>130):
                fail(f"{region}: invalid speedLimit")

            direction=p.get("direction")
            if direction is not None and (not isinstance(direction,(int,float)) or not 0.0<=float(direction)<360.0):
                fail(f"{region}: invalid direction")

            length=p.get("sectionLength")
            if length is not None and (not isinstance(length,(int,float)) or float(length)<=0):
                fail(f"{region}: invalid sectionLength")

        total_points+=len(points)

    print(json.dumps({"regions":len(seen_regions),"points":total_points},ensure_ascii=False))

if __name__=="__main__":
    main()
