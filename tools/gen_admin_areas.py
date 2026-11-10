"""최신 행정동 경계(vuski/admdongkor, 2026-10 기준)에서 읍·면·동별 대표 좌표 CSV를 만든다.
실행: python tools/gen_admin_areas.py   (먼저 tools/data/hangjeongdong_latest.geojson을 내려받아 둔다)
출력: src/main/resources/data/admin_areas.csv  — 시도(약칭), 시군구, 읍면동, 종류(동/읍/면), 위도, 경도
데이터 출처: https://github.com/vuski/admdongkor (행정동 경계, LICENSE-DATA 참고). 원본 경계 파일은 저장소에 넣지 않는다.
"""
import io
import json
import os
import collections

HERE = os.path.dirname(__file__)
SRC = os.path.join(HERE, "data", "hangjeongdong_latest.geojson")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "admin_areas.csv")

SHORT = {
    "서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구", "인천광역시": "인천", "광주광역시": "광주",
    "대전광역시": "대전", "울산광역시": "울산", "전남광주통합특별시": "전남광주", "세종특별자치시": "세종", "경기도": "경기", "강원특별자치도": "강원",
    "강원도": "강원", "충청북도": "충북", "충청남도": "충남", "전북특별자치도": "전북", "전라북도": "전북",
    "전라남도": "전남", "경상북도": "경북", "경상남도": "경남", "제주특별자치도": "제주",
}


def ring_area_centroid(ring):
    """폴리곤 바깥 고리의 면적과 무게중심(경도, 위도)"""
    a = cx = cy = 0.0
    n = len(ring)
    for i in range(n - 1):
        x0, y0 = ring[i][0], ring[i][1]
        x1, y1 = ring[i + 1][0], ring[i + 1][1]
        cr = x0 * y1 - x1 * y0
        a += cr
        cx += (x0 + x1) * cr
        cy += (y0 + y1) * cr
    if abs(a) < 1e-12:
        return 0.0, ring[0][0], ring[0][1]
    a *= 0.5
    return abs(a), cx / (6 * a), cy / (6 * a)


def main():
    with io.open(SRC, encoding="utf-8") as f:
        data = json.load(f)
    rows = []
    for ft in data["features"]:
        p = ft["properties"]
        g = ft["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        best = max((ring_area_centroid(poly[0]) for poly in polys), key=lambda t: t[0])
        sido = SHORT.get(p["sidonm"], p["sidonm"])
        sgg = p["sggnm"].strip()
        dong = p["adm_nm"].split(" ")[-1].replace(",", "·")  # 두류1,2동 같은 이름의 쉼표는 CSV를 깨뜨린다
        kind = "읍" if dong.endswith("읍") else ("면" if dong.endswith("면") else "동")
        rows.append((sido, sgg, dong, kind, round(best[2], 5), round(best[1], 5)))
    rows.sort()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("sido,sgg,dong,kind,lat,lng\n")
        for r in rows:
            f.write("%s,%s,%s,%s,%s,%s\n" % r)
    by = collections.Counter((r[0], r[3]) for r in rows)
    sidos = sorted({r[0] for r in rows})
    print("총 %d개 (동/읍/면)" % len(rows))
    for s in sidos:
        print("%s: 동 %d · 읍 %d · 면 %d" % (s, by[(s, "동")], by[(s, "읍")], by[(s, "면")]))
    print("시군구 수:", len({(r[0], r[1]) for r in rows}))


if __name__ == "__main__":
    main()
