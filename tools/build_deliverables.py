"""산출물 만들기 (PDF · 엑셀 · PPT) — StudySeat.

내용은 docs/ 문서(요구사항·설계·테스트 결과·작업일지)와 실제 시험 결과(build/test-results)에서 가져온다. 손으로 다시 적은 숫자가 없도록 표는 문서에서 읽는다.
화면 이미지는 tools/shoot.py 로 찍은 것(docs/screenshots_new → deliverables/screenshots). 모두 가상(데모) 데이터다.
실행(PDF는 Chrome, 엑셀·PPT는 openpyxl·python-pptx 필요):  python tools/build_deliverables.py
"""
import html
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
OUT = ROOT / "deliverables"
SHOT = OUT / "screenshots"
CHROME = next((p for p in (r"C:\Program Files\Google\Chrome\Application\chrome.exe", r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe") if Path(p).exists()), None)
E = html.escape
TITLE = "StudySeat — 스터디카페 지점 찾기 · 도면 좌석 예약"
PERIOD = "2026-11-09 ~ (개인 토이 프로젝트)"


# ───────────────────────── 문서(md)에서 표 읽기 ─────────────────────────
def md_tables(path):
    """마크다운 표를 [(바로 앞 제목, 머리글, 행들)]로 읽는다."""
    out, head, lines = [], "", Path(path).read_text(encoding="utf-8").splitlines()
    i = 0
    while i < len(lines):
        ln = lines[i]
        if ln.startswith("#"):
            head = ln.lstrip("# ").strip()
        if ln.startswith("|") and i + 1 < len(lines) and re.match(r"^\|[\s:\-|]+\|$", lines[i + 1].strip()):
            hdr = [c.strip() for c in ln.strip().strip("|").split("|")]
            rows, i = [], i + 2
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip().replace("`", "") for c in re.split(r"(?<!\\)\|", lines[i].strip().strip("|"))])
                i += 1
            out.append((head, [h.replace("`", "") for h in hdr], rows))
            continue
        i += 1
    return out


def find_table(tables, first_header, head_part=None):
    for h, hdr, rows in tables:
        if hdr[0] == first_header and (head_part is None or head_part in h):
            return hdr, rows
    raise KeyError(first_header)


T_REQ = md_tables(DOCS / "01_요구사항_기능명세.md")
T_DES = md_tables(DOCS / "02_설계_ERD_API.md")
T_TST = md_tables(DOCS / "03_테스트_결과.md")
REQ_H, REQ = find_table(T_REQ, "ID")
POLICY_H, POLICY = find_table(T_REQ, "항목", "정책")
ROLE_H, ROLE = find_table(T_REQ, "기능")
PAGE_H, PAGES = find_table(T_DES, "메서드")
JSON_H, JSONAPI = find_table(T_DES, "경로")
JOB_H, JOBS = find_table(T_DES, "작업")
DEC_H, DECISIONS = find_table(T_DES, "결정")
MANUAL_H, MANUAL = find_table(T_TST, "항목", "수동")
REVIEW_H, REVIEWS = find_table(T_TST, "지적")
AUTO_GROUPS = [(h, rows) for h, hdr, rows in T_TST if hdr[:2] == ["시험", "검증 내용"] or hdr[:2] == ["시험", "검증"]]
UNVERIFIED = [ln[2:].strip().replace("**", "").replace("`", "") for ln in (DOCS / "03_테스트_결과.md").read_text(encoding="utf-8").split("## 5.")[1].splitlines() if ln.startswith("- ")]


def erd():
    """mermaid ERD → ({테이블: [(타입, 컬럼, 키, 설명)]}, [(부모, 자식, 설명)])"""
    src = (DOCS / "02_설계_ERD_API.md").read_text(encoding="utf-8")
    block = src.split("```mermaid")[1].split("```")[0]
    tabs, rels = {}, []
    for m in re.finditer(r"(\w+) \{(.*?)\n    \}", block, re.S):
        cols = []
        for ln in m.group(2).strip().splitlines():
            mm = re.match(r"\s*(\w+)\s+(\w+)\s*(PK|FK|UK)?\s*(?:\"(.*)\")?", ln)
            if mm:
                cols.append((mm.group(1), mm.group(2), mm.group(3) or "", mm.group(4) or ""))
        tabs[m.group(1)] = cols
    for m in re.finditer(r"(\w+) \|\|--(?:o\{|\|\{|o\|) (\w+) : \"(.*?)\"", block):
        rels.append((m.group(1), m.group(2), m.group(3)))
    return tabs, rels


ERD_TABLES, ERD_RELS = erd()


def test_results():
    """gradlew test 결과 XML에서 시험 수를 읽는다(없으면 빈 목록)."""
    rows = []
    for f in sorted((ROOT / "build" / "test-results" / "test").glob("TEST-*.xml")):
        r = ET.parse(f).getroot()
        rows.append((r.get("name").split(".")[-1], int(r.get("tests")), int(r.get("failures")) + int(r.get("errors")), int(r.get("skipped"))))
    return rows


RESULTS = test_results()
N_TESTS = sum(r[1] for r in RESULTS)
N_FAIL = sum(r[2] for r in RESULTS)
N_SKIP = sum(r[3] for r in RESULTS)
TEST_LINE = f"자동 시험 {N_TESTS}개 중 실패 {N_FAIL}, 건너뜀 {N_SKIP}" if RESULTS else "자동 시험 결과 없음(gradlew test 필요)"


def md_html(text):
    """작업일지용 아주 작은 마크다운 → HTML (제목·목록·굵게·코드·링크)."""
    out, depth = [], 0

    def inline(s):
        s = E(s)
        s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", s)
        s = re.sub(r"`(.+?)`", r"<code>\1</code>", s)
        return re.sub(r"\[(.+?)\]\((.+?)\)", r"\1", s)

    for ln in text.splitlines():
        m = re.match(r"^(\s*)- (.*)", ln)
        if m:
            d = len(m.group(1)) // 2 + 1
            while depth < d:
                out.append("<ul>"); depth += 1
            while depth > d:
                out.append("</ul>"); depth -= 1
            out.append(f"<li>{inline(m.group(2))}</li>")
            continue
        while depth:
            out.append("</ul>"); depth -= 1
        if ln.startswith("### "):
            out.append(f"<h3>{inline(ln[4:])}</h3>")
        elif ln.startswith("## "):
            out.append(f"<h2>{inline(ln[3:])}</h2>")
        elif ln.startswith("# "):
            out.append(f"<h1>{inline(ln[2:])}</h1>")
        elif ln.strip():
            out.append(f"<p>{inline(ln)}</p>")
    while depth:
        out.append("</ul>"); depth -= 1
    return "".join(out)


# ───────────────────────── PDF 도우미 ─────────────────────────
def pdf(name, body, landscape=True):
    htmlfile = OUT / "_tmp.html"
    css = """@page{size:A4 %s;margin:14mm}body{font-family:"Malgun Gothic","Noto Sans KR",sans-serif;color:#1b2430;font-size:12.5px;line-height:1.7}
h1{font-size:25px;margin:0 0 4px;color:#0b3d3a}h2{font-size:16px;margin:20px 0 6px;color:#0f6f69;border-left:4px solid #14a89c;padding-left:8px}h3{font-size:14px;margin:14px 0 4px;color:#0b3d3a}
.sub{color:#5b6b80;margin-bottom:14px}table{border-collapse:collapse;width:100%%;margin:8px 0;font-size:11.5px}th{background:#0f766e;color:#fff;text-align:left}
th,td{border:1px solid #cfd8e3;padding:5px 8px;vertical-align:top}tr:nth-child(even) td{background:#f5f8fb}.box{background:#eef8f6;border:1px solid #c6e4df;border-radius:6px;padding:10px 14px;margin:8px 0}
img{max-width:100%%;max-height:165mm;border:1px solid #cfd8e3;border-radius:4px}.cap{color:#5b6b80;font-size:11px;margin-bottom:10px}ul{margin:4px 0 4px 18px;padding:0}p{margin:4px 0}
pre{background:#0f1b2d;color:#e6edf6;padding:10px 12px;border-radius:6px;font-size:11px;line-height:1.5;white-space:pre-wrap}code{background:#eef2f6;padding:0 4px;border-radius:3px;font-size:11px}pre code{background:none;padding:0}
.grid{display:flex;gap:10px;flex-wrap:wrap}.grid>div{flex:1 1 30%%}footer{margin-top:18px;color:#8a96a6;font-size:10.5px;border-top:1px solid #dde4ec;padding-top:6px}""" % ("landscape" if landscape else "portrait")
    htmlfile.write_text(f'<!doctype html><html lang="ko"><head><meta charset="utf-8"><style>{css}</style></head><body>{body}<footer>{E(TITLE)} · 개인 프로젝트 · 지점·예약·회원 정보는 모두 가상(데모) 데이터</footer></body></html>', encoding="utf-8")
    target = OUT / name
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", f"--print-to-pdf={target}", htmlfile.as_uri()], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    print("PDF", name, target.exists())


def table(headers, rows):
    th = "".join(f"<th>{E(h)}</th>" for h in headers)
    tr = "".join("<tr>" + "".join(f"<td>{E(str(c))}</td>" for c in r) + "</tr>" for r in rows)
    return f"<table><tr>{th}</tr>{tr}</table>"


def img(fname, cap, h=None):
    p = SHOT / fname
    st = f' style="max-height:{h}mm"' if h else ""
    return f'<img src="{p.as_uri()}"{st}><div class="cap">{E(cap)}</div>' if p.exists() else ""


DIAGRAM_CALLS = []   # svg_boxes 호출 기록(발표용 PPT에서 같은 데이터를 도형으로 다시 그린다)


def svg_boxes(w, h, boxes, arrows):
    DIAGRAM_CALLS.append((w, h, boxes, arrows))
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" font-family="Malgun Gothic,sans-serif" font-size="12">',
         '<defs><marker id="a" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#5b6b80"/></marker></defs>']
    for x1, y1, x2, y2, lab in arrows:
        s.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="#5b6b80" stroke-width="1.4" marker-end="url(#a)"/>')
        if lab:
            s.append(f'<text x="{(x1 + x2) / 2 + 4}" y="{(y1 + y2) / 2 - 4}" fill="#5b6b80" font-size="10.5">{E(lab)}</text>')
    for x, y, bw, bh, lab, fill in boxes:
        s.append(f'<rect x="{x}" y="{y}" width="{bw}" height="{bh}" rx="8" fill="{fill}" stroke="#0b3d3a" stroke-width="1.2"/>')
        n = len(lab.split("\n"))
        for i, line in enumerate(lab.split("\n")):
            s.append(f'<text x="{x + bw / 2}" y="{y + bh / 2 + 4 + (i - (n - 1) / 2) * 15}" text-anchor="middle" fill="#0b3d3a">{E(line)}</text>')
    s.append("</svg>")
    return "".join(s)


ARCH = svg_boxes(940, 400, [
    (20, 150, 170, 80, "브라우저\nThymeleaf 화면\nLeaflet 지도 · Chart.js", "#e4f6f5"),
    (260, 150, 190, 80, "Spring MVC\n컨트롤러(web)\n+ Spring Security", "#dcecff"),
    (520, 150, 190, 80, "서비스(service)\n예약·포인트·도면\n통계·시더", "#fff3d6"),
    (780, 150, 140, 80, "JPA · JDBC\n(repository)", "#e8ffe9"),
    (780, 290, 140, 80, "H2(MySQL 모드)\n또는 MySQL 8\nFlyway V1~V6", "#f3e8ff"),
    (520, 20, 190, 80, "스케줄러\n1분: 노쇼·자동 종료\n매시 5분: 혼잡도 집계", "#ffeee6"),
    (260, 290, 190, 80, "도면 엔진\nFloorPlanLayout\n(좌석 수 → 건축 도면)", "#eef0f3"),
    (20, 20, 190, 80, "시작 작업\n전국 지점·데모 데이터\n누락 통계 보충", "#ffeee6"),
], [(190, 190, 260, 190, "HTTP"), (450, 190, 520, 190, ""), (710, 190, 780, 190, ""), (850, 230, 850, 290, "SQL"), (615, 100, 615, 150, "호출"), (355, 290, 355, 230, "도면 좌표")])

USECASE = svg_boxes(940, 460, [
    (20, 60, 110, 60, "비회원", "#e4f6f5"), (20, 200, 110, 60, "회원", "#e4f6f5"), (20, 360, 110, 60, "관리자", "#e4f6f5"),
    (290, 10, 260, 36, "지도·지점 상세·도면 현황 보기", "#dcecff"), (290, 54, 260, 36, "후기 보기 · 키오스크(코드) 입퇴실", "#dcecff"),
    (290, 110, 260, 36, "가입 · 로그인", "#fff3d6"),
    (290, 168, 260, 36, "도면에서 좌석 예약 · 취소", "#e8ffe9"), (290, 212, 260, 36, "입실(QR·코드) · 퇴실 · 연장", "#e8ffe9"),
    (290, 256, 260, 36, "포인트 충전 · 내 지점 등록", "#e8ffe9"), (290, 300, 260, 36, "후기 작성 · 1:1 문의", "#e8ffe9"),
    (290, 360, 260, 36, "지점 현황·혼잡도 그래프 · 통계 보충", "#ffeee6"), (290, 404, 260, 36, "좌석 켜기/끄기 · 예약 강제 취소 · 문의 답변", "#ffeee6"),
    (660, 168, 250, 36, "스케줄러: 노쇼 · 자동 종료 · 집계", "#eef0f3"),
], [(130, 90, 290, 28, ""), (130, 90, 290, 72, ""), (130, 90, 290, 128, ""), (130, 230, 290, 186, ""), (130, 230, 290, 230, ""), (130, 230, 290, 274, ""), (130, 230, 290, 318, ""),
    (130, 230, 290, 28, ""), (130, 390, 290, 378, ""), (130, 390, 290, 422, ""), (660, 186, 550, 230, "")])


def erd_svg():
    pos = {"branch": (40, 20), "zone": (250, 20), "seat": (460, 20), "plan_object": (670, 20),
           "favorite_branch": (40, 140), "member": (250, 140), "reservation": (460, 140), "reservation_slot": (670, 140),
           "inquiry": (40, 260), "point_ledger": (250, 260), "review": (460, 260), "occupancy_hourly": (670, 260),
           "cafe_policy": (40, 360), "occupancy_daily": (670, 360)}
    bw, bh = 170, 54
    boxes, arrows = [], []
    for t, (x, y) in pos.items():
        if t in ERD_TABLES:
            boxes.append((x, y, bw, bh, f"{t}\n({len(ERD_TABLES[t])}개 컬럼)", "#e4f6f5" if t in ("member", "reservation", "reservation_slot", "point_ledger") else "#eef8f6"))
    for a, b, lab in ERD_RELS:
        if a in pos and b in pos:
            ax, ay = pos[a][0] + bw / 2, pos[a][1] + bh / 2
            bx, by = pos[b][0] + bw / 2, pos[b][1] + bh / 2
            dx, dy = bx - ax, by - ay
            k = max(abs(dx) / (bw / 2), abs(dy) / (bh / 2)) or 1
            arrows.append((round(ax + dx / k * 1.0), round(ay + dy / k * 1.0), round(bx - dx / k), round(by - dy / k), ""))
    return svg_boxes(900, 440, boxes, arrows)


# ───────────────────────── PDF ─────────────────────────
def build_pdfs():
    OUT.mkdir(exist_ok=True)
    n_req = len(REQ)
    pdf("01_프로젝트명_소개.pdf", f"<h1>{E(TITLE)}</h1><div class='sub'>{PERIOD}</div>"
        "<div class='box'><b>한 줄 소개</b> — 여러 지점의 스터디카페를 <b>지도에서 찾고</b>, 매장 <b>도면에서 좌석을 직접 눌러 예약</b>하고, <b>QR로 입실</b>하는 서비스. 관리자는 지점별 혼잡도를 한 화면에서 본다.</div>"
        + table(["항목", "내용"], [("프로젝트명", "StudySeat"), ("유형", "개인 토이 프로젝트(1인) · 신입 백엔드 포트폴리오용"), ("기간", PERIOD),
                              ("기술", "Java 17 · Spring Boot 4.1.2 · Spring Data JPA · Spring Security(세션) · Thymeleaf · Leaflet(OpenStreetMap) · Chart.js · Flyway · H2(MySQL 모드)/MySQL 8 · JUnit 5 · Gradle"),
                              ("규모", "가상 지점 약 720곳(실제 시·군·구 256곳 위에 배치) · 좌석 약 1만 5천 석 · 요구사항 " + str(n_req) + "개"), ("검증", TEST_LINE + " (H2 MySQL 모드)")])
        + img("home.png", "홈 화면(가상 데이터)", 120))
    pdf("02_주제선정배경_기획의도.pdf", "<h1>주제 선정 배경 · 기획 의도</h1><div class='sub'>왜 만들었나</div>"
        "<h2>배경</h2><ul><li>신입 백엔드 포트폴리오로, <b>동시성 · 트랜잭션 · 데이터 모델링</b>을 보여 줄 수 있는 '예약' 소재를 찾았다.</li>"
        "<li>방탈출 카페 예약 서비스 <b>OBM(chuyj15/OBM, JSP/Servlet)</b>의 뼈대(시간대 선택 예약, 포인트, 후기, 1:1 문의, 관리자 취소)에서 <b>아이디어만</b> 가져와 스터디카페 소재로 바꾸고 Spring Boot로 새로 설계했다(코드 사용 없음).</li></ul>"
        "<h2>첫 버전의 한계와 방향 전환</h2><ul><li>처음엔 단일 열람실 표 UI였다. \"너무 대충 만들었다, 진짜 서비스처럼 지도도 열어서 예약하듯이\"라는 피드백을 받아 <b>실제 스터디카페 앱을 조사</b>하고 전면 재작성했다.</li>"
        "<li>이후 \"서울만 나온다·너무 적다\"는 지적으로 <b>전국 · 실제 행정구역 기준</b> 지점 배치, \"AI 느낌이 난다\"는 지적으로 <b>디자인 패치</b>(Pretendard·Lucide, 단색 정리)를 했다.</li></ul>"
        "<h2>목표</h2>" + table(["목표", "내용"], [("1", "같은 좌석을 두 사람이 동시에 잡아도 이중 예약 0건 — 시험으로 증명"), ("2", "포인트를 원장으로 관리해 이중 사용·이중 환불 0건"),
                                          ("3", "도면에서 좌석을 직접 고르는 실제 서비스 같은 화면"), ("4", "지점별 운영 시간·요금·혼잡도 통계, 서버가 꺼져 있던 구간 보충"), ("5", "한 번에 돌려 보여 줄 수 있는 데모(자동 생성 데이터)")]))
    pdf("03_유사_서비스_분석·차별화.pdf", "<h1>유사 서비스 분석 · 차별화</h1><div class='sub'>국내 스터디카페 앱(렛미업·1PP·스터디봄 등)의 소개 문서에서 공통 기능을 정리</div>"
        + table(["실제 서비스에서 확인한 기능", "StudySeat에서의 구현"], [
            ("위치 기반으로 가까운 지점을 찾고, 자주 가는 지점을 '내 지점'으로 등록", "전국 지점 지도(Leaflet+OSM, 마커 묶음) + 시·도 칩 + 시·군·구 선택 + 검색, 잔여석·거리순 정렬, 내 지점"),
            ("좌석·스터디룸 현황을 실시간으로 확인", "지점별 도면에 좌석 상태 표시, 지도 마커에 지금 잔여석"),
            ("좌석(일반/프리미엄)과 스터디룸마다 요금이 다름", "구역별 시간당 요금(일반석·라운지·스터디룸 등)"), ("이용 시간 연장", "이용 중 +1/+2시간 연장(바로 뒤 시간이 비어 있을 때, 예약당 1회)"),
            ("QR 코드로 키오스크 입·퇴실", "예약 후 QR + 6자리 코드, 키오스크 시뮬레이터"), ("충전 포인트·마일리지", "포인트 충전(데모 결제) + 보너스, 원장 내역"), ("지점마다 다른 운영 시간(24시간 지점 포함)", "지점별 운영 시간")])
        + "<h2>차별점 (내 것으로 만든 부분)</h2><ul><li><b>동시성</b>: 예약을 1시간 슬롯 행으로 쪼개 <code>(seat_id, slot_start)</code> 유니크 제약이 DB 단에서 이중 예약을 거부 — 20명 동시 예약 시험으로 증명</li>"
          "<li><b>포인트 원장</b>: 잔액은 원장 합계, <code>(reservation_id, reason)</code> 유니크로 환불·적립 중복 차단</li><li><b>도면 엔진</b>: 좌석 수만 주면 건축 도면처럼 배치(756가지 조합에서 겹침 0 확인)</li>"
          "<li><b>혼잡도 통계</b>: 시간별·일별 집계 + 꺼져 있던 구간 자동 보충</li></ul>"
          "<div class='box'><b>한계</b> — 조사한 자료는 앱스토어 소개문·고객센터 문서 수준이라 화면 구성의 세부는 새로 설계했습니다. 지점 이름·주소·전화는 모두 가상의 예시입니다.</div>")
    feat = [("누구나", "지도·지점 상세·도면 현황·후기 조회, 키오스크 시뮬레이터"), ("회원", "가입(환영 20,000P)·로그인, 도면 좌석 예약, QR 입실·퇴실, 이용 시간 연장, 시작 60분 전 취소(전액 환불), 포인트 충전·내역, 내 지점, 후기, 1:1 문의"),
            ("관리자", "지점 실시간 현황, 구역별 시간별·일별 혼잡도 그래프, 누락 통계 보충, 좌석 켜기/끄기, 예약 강제 취소(환불), 문의 답변")]
    pdf("04_주요_기능_서비스_컨셉.pdf", "<h1>주요 기능 · 서비스 컨셉</h1><div class='sub'>찾기 → 고르기 → 예약 → 입실 → 이용 후 적립·후기</div>" + table(["대상", "기능"], feat)
        + "<h2>정책 값</h2>" + table(POLICY_H, POLICY) + img("home.png", "홈", 100) + img("branches-map.png", "지점 찾기(지도 + 지점 카드)", 120))
    pdf("05_개발환경.pdf", "<h1>개발 환경</h1>" + table(["구분", "내용"], [("언어·프레임워크", "Java 17, Spring Boot 4.1.2, Spring Data JPA, Spring Security(세션), Thymeleaf"), ("화면", "Thymeleaf + 직접 작성한 CSS/JS, Leaflet(OpenStreetMap), Chart.js, Pretendard(글꼴), Lucide(아이콘)"),
        ("DB", "기본 H2(MySQL 모드, 파일 없음·매번 새 데이터) / MySQL 8 선택(DB_URL 환경변수), 스키마는 Flyway V1~V6"), ("빌드·시험", "Gradle 9.8.1(wrapper), JUnit 5"), ("개발 PC", "Windows 10, JDK 17. Docker·MySQL은 설치되어 있지 않음"),
        ("실행", "gradlew.bat bootRun → http://localhost:8080 (데이터가 채워지기까지 약 15~25초)"), ("체험 계정(로컬 데모용)", "일반 회원 demo / 관리자 admin — README 참고. 실서비스에 올릴 때는 없애거나 바꿔야 함"),
        ("외부 연결", "지도 타일과 Chart.js·Leaflet·QR 라이브러리는 CDN에서 받아 오므로 인터넷이 필요")])
        + "<h2>주요 패키지</h2>" + table(["패키지", "역할"], [("config", "보안·시계(Clock, Asia/Seoul)·스케줄러·시작 작업"), ("domain", "엔티티(지점·구역·좌석·예약·슬롯·원장·후기·문의 등)"), ("repository", "JPA 저장소(회원·예약 행 잠금 조회 포함)"),
                                                          ("service", "예약·포인트·도면 엔진·통계·전국 시더 등 업무 규칙"), ("web", "컨트롤러·도면 변환·JSON API")]))
    pdf("06_활용방안_기대효과.pdf", "<h1>활용 방안 · 기대 효과</h1><div class='sub'>개인 프로젝트이므로 '이렇게 쓸 수 있다'는 가능성과 지금 상태를 구분해 적었습니다</div>"
        "<h2>지금 가능한 것</h2><ul><li>신입 백엔드 포트폴리오: 동시성·트랜잭션·원장·집계 설계와 그것을 증명하는 <b>자동 시험</b>을 보여 줄 수 있다.</li><li>한 번에 실행되는 데모(가상 지점 약 720곳, 예약·통계 자동 생성)로 면접·시연에서 바로 시연 가능.</li></ul>"
        "<h2>확장하려면 (아직 하지 않음)</h2>" + table(["항목", "현재", "필요한 일"], [("결제", "데모 결제", "실제 PG 연동"), ("입실", "코드 입력 키오스크 시뮬레이터", "QR 리더·단말 인증"), ("혼잡도", "예약 슬롯 기준", "착석 센서 값 연동"),
                                                                           ("DB", "H2(MySQL 모드)에서만 시험", "MySQL 8 실제 서버에서 락·격리 재확인, Docker Compose"), ("배포·CI", "없음", "GitHub Actions(빌드+테스트)")]))
    pdf("10_유스케이스_다이어그램.pdf", "<h1>유스케이스 다이어그램</h1><div class='sub'>사용자별로 할 수 있는 일</div>" + USECASE + table(ROLE_H, ROLE))
    pdf("11_화면_구성(실제_화면).pdf", "<h1>화면 구성 (실제 실행 화면)</h1><div class='sub'>와이어프레임 대신 실제 앱을 직접 실행해 찍은 화면입니다(가상 데이터)</div>"
        + img("home.png", "홈", 105) + img("branches-map.png", "지점 찾기 — 전국 지도와 지점 카드", 120) + img("branch-detail.png", "지점 상세 — 요금표·시간대별 혼잡도·후기", 150)
        + img("booking-daejeon.png", "좌석 예약 — 대전 궁동점(홀형 도면)", 170) + img("booking-konkuk.png", "좌석 예약 — 건대입구점(도서관형 도면)", 170) + img("booking-rural.png", "좌석 예약 — 작은 지점(인천 옹진군 대청면점)", 170)
        + img("ticket-qr.png", "예약 완료 · 입실 QR · 연장", 170) + img("my-reservations.png", "내 예약", 150) + img("points.png", "포인트 충전·내역", 150) + img("kiosk.png", "키오스크 시뮬레이터", 120)
        + img("admin-dashboard.png", "관리자 대시보드", 170) + img("login.png", "로그인", 100) + img("signup.png", "가입", 100))
    pdf("12_시스템·서비스_구성도.pdf", "<h1>시스템 · 서비스 구성도</h1>" + ARCH
        + "<ul><li>시간은 <code>Clock</code>(Asia/Seoul)을 주입해 쓰고, 서비스 메서드는 <code>now</code>를 인자로 받아 시험에서 시각을 고정할 수 있다.</li><li>스키마는 Flyway(V1~V6), JPA는 <code>ddl-auto: validate</code>로 일치만 검사한다.</li><li>POST 요청은 모두 CSRF 토큰이 필요하다.</li></ul>"
        + "<h2>스케줄 · 시작 작업</h2>" + table(JOB_H, JOBS))
    pdf("13_ERD_설계결정.pdf", "<h1>ERD · 설계 결정</h1>" + erd_svg() + "<div class='cap'>선·화살표는 테이블 사이의 연결(자세한 컬럼은 14_데이터_테이블_명세서.xlsx)</div>" + "<h2>설계 결정</h2>" + table(DEC_H, DECISIONS))
    code = (ROOT / "src/main/java/com/studyseat/service/ReservationService.java").read_text(encoding="utf-8").split("\n")
    snippet = "\n".join(code[55:90])
    ddl = "\n".join((ROOT / "src/main/resources/db/migration/V1__init.sql").read_text(encoding="utf-8").split("\n")[57:67])
    conc = [r for g, rows in AUTO_GROUPS for r in rows if g.startswith("동시성") or "동시" in r[0]][:6]
    pdf("16_주요_기능_구현·핵심_코드.pdf", "<h1>핵심 구현 — 동시 예약 방지와 포인트 이중 사용 방지</h1>"
        "<h2>1) 예약 (ReservationService.reserve)</h2><pre><code>" + E(snippet) + "</code></pre>"
        "<h2>2) 슬롯 테이블과 유니크 제약 (V1__init.sql)</h2><pre><code>" + E(ddl) + "</code></pre>"
        "<div class='box'><b>핵심</b> — ① 회원 행을 <b>트랜잭션 첫 문장</b>에서 잠가(비관적 락) 같은 회원의 동시 예약이 포인트를 이중으로 쓰지 못하게 하고, ② 예약을 1시간 슬롯 행으로 쪼개 <code>(seat_id, slot_start)</code> 유니크 제약이 겹치는 예약을 DB가 거부하게 한다. ③ 격리 수준은 READ COMMITTED.</div>"
        "<h2>증명한 시험</h2>" + table(["시험", "검증 내용"], conc))
    pdf("18_성과·목표_달성도.pdf", "<h1>성과 · 목표 달성도</h1>" + table(["목표", "결과", "근거"], [
        ("이중 예약 0건", "달성", "20명 동시 예약 → 1명 성공·19명 거부, 겹치는 예약 12건 혼합 → 중복 슬롯 0건"), ("포인트 이중 사용 방지", "달성", "잔액 2,000P로 5좌석 동시 예약 → 1건 성공, 잔액 0. 락을 빼면 시험이 실패함을 확인"),
        ("환불·적립 중복 방지", "달성", "같은 예약을 5명이 동시에 취소 → 환불 1건"), ("도면 좌석 선택", "달성", "도면 엔진 756가지 조합에서 겹침 0. 홀형·도서관형 4스타일"), ("전국 지점", "달성", "256개 시·군·구 모두 최소 1곳, 읍·면·동은 실제 행정구역"),
        ("누락 통계 보충", "달성", "한 시간만 지우면 그 시간만 복구, 다시 돌려도 0건"), ("MySQL 8 실서버 검증", "미달성", "H2(MySQL 모드)에서만 시험"), ("모바일 화면 확인", "미확인", "반응형 CSS는 작성했으나 실제 폭에서 보지 못함")])
        + f"<div class='box'><b>검증</b> — {E(TEST_LINE)}</div>" + img("admin-dashboard.png", "관리자 대시보드", 110))
    pdf("19_아쉬운_점·개선_계획.pdf", "<h1>아쉬운 점 · 개선 계획</h1><h2>확인하지 못한 것</h2><ul>" + "".join(f"<li>{E(x)}</li>" for x in UNVERIFIED) + "</ul>"
        "<h2>개선 계획</h2><ul><li>MySQL 8에서 동시성 시험 재확인, Docker Compose, GitHub Actions(빌드+테스트)</li><li>웹 계층 자동 시험(컨트롤러·템플릿), 입실 성공 경로 화면 확인</li><li>시연 영상, 포트폴리오에 3번째 프로젝트로 추가</li></ul>")
    pdf("20_프로젝트_수행일지.pdf", md_html((DOCS / "04_작업일지.md").read_text(encoding="utf-8")), landscape=False)


# ───────────────────────── 엑셀 ─────────────────────────
WBS = [  # (단계, 작업, 시작, 종료, 상태)
    ("기획", "참고 서비스(OBM) 분석, 범위·일정, 요구사항·ERD·API 초안, Gemini 설계 리뷰 반영", "2026-11-09", "2026-11-09", "완료"),
    ("기본 예약", "좌석 예약 + 동시 예약 방지, 포인트 원장, 취소·환불, 체크인·퇴실, 노쇼·자동 종료, 후기·문의, 혼잡도 통계, 관리자 화면(시험 13개)", "2026-11-09", "2026-11-09", "완료"),
    ("서비스 확장", "지점·구역 가격·도면 좌표·내 지점·예약 코드, 연장, 키오스크, 충전, 지도·지점 상세 화면(시험 22개)", "2026-11-09", "2026-11-09", "완료"),
    ("전국 지점", "실제 행정구역(256개 시·군·구) 기준 지점 약 720곳, 지도 JSON API, 대용량 시드(JDBC 배치)", "2026-11-09", "2026-11-09", "완료"),
    ("도면", "도면 엔진(FloorPlanLayout), 성별 전용·고정석·퇴실예정석, 재배치(V5·V6), 시험 40개", "2026-11-09", "2026-11-09", "완료"),
    ("홈·디자인", "홈 화면 분리, 디자인 패치(Pretendard·Lucide), 로고", "2026-11-09", "2026-11-09", "완료"),
    ("산출물", "문서·엑셀·PPT·스크린샷 정리(이 문서 세트)", "2026-11-10", "2026-11-10", "완료"),
    ("업로드", "GitHub에 날짜별로 나누어 업로드(실제 날짜로 커밋)", "미정", "미정", "예정"),
    ("후속", "MySQL 8 실서버 검증, Docker Compose, GitHub Actions, 웹 계층 시험, 시연 영상", "미정", "미정", "예정"),
]


def build_xlsx():
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font, PatternFill
    from openpyxl.utils import get_column_letter

    def save(name, sheets):
        wb = Workbook()
        wb.remove(wb.active)
        for title, headers, rows, widths in sheets:
            ws = wb.create_sheet(title[:31])
            ws.append(headers)
            for r in rows:
                ws.append(list(r))
            for c in ws[1]:
                c.font = Font(bold=True, color="FFFFFF")
                c.fill = PatternFill("solid", fgColor="0F766E")
            for i, w in enumerate(widths, 1):
                ws.column_dimensions[get_column_letter(i)].width = w
            for row in ws.iter_rows(min_row=2):
                for c in row:
                    c.alignment = Alignment(wrap_text=True, vertical="top")
            ws.freeze_panes = "A2"
        wb.save(OUT / name)
        print("XLSX", name)

    # 07_WBS.xlsx 는 tools/build_wbs.py 가 만든다(번호·담당·간트 형식)
    save("08_요구사항_명세서.xlsx", [("요구사항", REQ_H, REQ, [10, 8, 90, 36]), ("정책 값", POLICY_H, POLICY, [20, 90])])
    save("09_기능_명세서_목록.xlsx", [("기능 목록", REQ_H, REQ, [10, 8, 90, 36]), ("사용자별 권한", ROLE_H, ROLE, [54, 10, 10, 10]),
                               ("예약 상태", ["상태", "다음 상태", "조건"], [("BOOKED(예약)", "CHECKED_IN(이용 중)", "입실"), ("CHECKED_IN", "DONE(이용 완료)", "퇴실 또는 종료 시각 자동 종료"), ("BOOKED", "CANCELED(취소)", "시작 60분 전까지 취소 / 관리자 강제 취소"),
                                                                     ("BOOKED", "NO_SHOW(노쇼)", "입실 유예(10분) 초과"), ("CHECKED_IN", "CANCELED", "관리자 강제 취소")], [18, 24, 50])])
    cols = [(t, c[0], c[1], c[2], c[3]) for t, cs in ERD_TABLES.items() for c in cs]
    cons = [("reservation_slot", "uk_slot (seat_id, slot_start)", "같은 좌석·같은 시간 이중 예약을 DB가 거부"), ("point_ledger", "uk_pl_once (reservation_id, reason)", "같은 예약에 같은 사유(환불·적립·연장)가 두 번 기록되지 않음"),
            ("member", "uk_member_login (login_id)", "아이디 중복 방지"), ("seat", "uk_seat_label (zone_id, label)", "구역 안에서 좌석 번호 중복 방지"), ("review", "uk_review_rsv (reservation_id)", "예약당 후기 1개"),
            ("zone", "(branch_id, name) 유니크", "지점 안에서만 구역 이름이 유일(지점마다 '일반석' 허용)")]
    save("14_데이터_테이블_명세서.xlsx", [("컬럼", ["테이블", "컬럼", "타입", "키", "설명"], cols, [22, 22, 12, 6, 50]), ("제약·인덱스", ["테이블", "제약", "역할"], cons, [20, 40, 70]),
                                    ("관계", ["부모", "자식", "설명"], ERD_RELS, [22, 22, 30])])
    save("15_API_명세서.xlsx", [("화면(HTML)", PAGE_H, PAGES, [10, 44, 20, 80]), ("JSON(읽기 전용)", JSON_H, JSONAPI, [40, 100])])
    summary = [(n, c, c - f - s, f, s) for n, c, f, s in RESULTS] + [("합계", N_TESTS, N_TESTS - N_FAIL - N_SKIP, N_FAIL, N_SKIP)]
    sheets = [("시험 요약(실행 결과)", ["시험 클래스", "시험 수", "통과", "실패", "건너뜀"], summary, [34, 10, 10, 10, 10])]
    for g, rows in AUTO_GROUPS:
        sheets.append((re.sub(r"[\\/*?:\[\]]", "", g)[:28], ["시험", "검증 내용"], rows, [50, 100]))
    sheets += [("화면·HTTP 수동 확인", MANUAL_H, MANUAL, [90, 70]), ("코드 리뷰 반영", REVIEW_H, REVIEWS, [60, 30, 60]), ("미확인", ["항목"], [(x,) for x in UNVERIFIED], [140])]
    save("17_테스트·검증_결과.xlsx", sheets)


# ───────────────────────── PPT ─────────────────────────
def build_pptx():
    from PIL import Image
    from pptx import Presentation
    from pptx.dml.color import RGBColor
    from pptx.util import Inches, Pt

    prs = Presentation()
    prs.slide_width, prs.slide_height = Inches(13.333), Inches(7.5)
    BG, FG, ACC, SUB = RGBColor(255, 255, 255), RGBColor(20, 32, 46), RGBColor(15, 118, 110), RGBColor(100, 116, 139)
    blank = prs.slide_layouts[6]

    def pic_fit(s, name, x, y, mw, mh):
        p = SHOT / name
        if not p.exists():
            return
        w, h = Image.open(p).size
        k = min(mw / w, mh / h)
        s.shapes.add_picture(str(p), Inches(x), Inches(y), width=Inches(w * k), height=Inches(h * k))

    KO = "Malgun Gothic"

    def slide(title, bullets=None, pic=None, note=None, pic_w=6.4, code=None):
        s = prs.slides.add_slide(blank)
        s.background.fill.solid(); s.background.fill.fore_color.rgb = BG
        bar = s.shapes.add_shape(1, 0, 0, Inches(0.18), prs.slide_height)
        bar.fill.solid(); bar.fill.fore_color.rgb = ACC; bar.line.fill.background()
        tb = s.shapes.add_textbox(Inches(0.6), Inches(0.35), Inches(12), Inches(0.9))
        p = tb.text_frame.paragraphs[0]; p.text = title; p.font.size = Pt(32); p.font.bold = True; p.font.color.rgb = FG; p.font.name = KO
        side = bool(pic or code)
        if bullets:
            bb = s.shapes.add_textbox(Inches(0.6), Inches(1.5), Inches(5.9 if side else 12), Inches(5.2)); tf = bb.text_frame; tf.word_wrap = True
            for i, b in enumerate(bullets):
                para = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
                para.text = "▸ " + b; para.font.size = Pt(18); para.font.color.rgb = FG; para.font.name = KO; para.space_after = Pt(10)
        if pic:
            pic_fit(s, pic, 6.7, 1.4, pic_w, 5.5)
        if code:
            cb = s.shapes.add_shape(1, Inches(6.7), Inches(1.5), Inches(6.2), Inches(5.2))
            cb.fill.solid(); cb.fill.fore_color.rgb = RGBColor(15, 27, 45); cb.line.fill.background()
            tf = cb.text_frame; tf.word_wrap = True
            for i, ln in enumerate(code.split("\n")):
                para = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
                para.text = ln; para.font.size = Pt(11.5); para.font.name = "Consolas"; para.font.color.rgb = RGBColor(230, 237, 246)
                para.alignment = 1
        if note:
            nb = s.shapes.add_textbox(Inches(0.6), Inches(6.95), Inches(12), Inches(0.4)); q = nb.text_frame.paragraphs[0]; q.text = note; q.font.size = Pt(11); q.font.color.rgb = SUB; q.font.name = KO
        return s

    slide("StudySeat", ["스터디카페 지점 찾기 · 도면 좌석 예약 서비스", "개인 토이 프로젝트 · 신입 백엔드 포트폴리오 · " + PERIOD, "Java 17 · Spring Boot · JPA · Thymeleaf · Flyway · JUnit 5"], "home.png", "지점·예약·회원 정보는 모두 가상(데모) 데이터입니다")
    slide("만든 이유", ["동시성·트랜잭션·데이터 모델링을 보여 줄 '예약' 소재", "방탈출 예약 OBM의 뼈대(아이디어만)를 스터디카페로 바꿔 새로 설계", "\"너무 대충 만들었다\" 피드백 → 실제 스터디카페 앱을 조사해 전면 재작성"])
    slide("실제 앱을 조사해서 반영", ["지점 찾기 + 내 지점 → 전국 지도 + 시·도 칩 + 검색", "실시간 좌석 현황 → 도면 상태 · 지도 마커 잔여석", "구역별 요금 · 연장 · QR 입실 · 충전 포인트 · 지점별 운영 시간"], "branches-map.png", "조사 자료는 앱스토어 소개문·고객센터 문서 수준 — 화면 구성의 세부는 새로 설계")
    slide("도면에서 좌석 선택", ["좌석 수만 주면 건축 도면처럼 배치하는 도면 엔진", "칸막이 책상 · 문 달린 스터디룸 · 키오스크 · 사물함", "여성/남성 전용 · 고정석 · 퇴실예정석을 도면과 서버 양쪽에서 검사"], "booking-daejeon.png")
    slide("핵심 ① 동시 예약 방지", ["예약을 1시간 슬롯 행으로 쪼갬", "(seat_id, slot_start) 유니크 제약이 DB 단에서 거부", "20명이 동시에 예약 → 1명 성공 · 19명 거부, 중복 슬롯 0건"], None, "ReservationService.reserve · V1__init.sql",
          code="CREATE TABLE reservation_slot (\n    reservation_id BIGINT NOT NULL,\n    seat_id        BIGINT NOT NULL,\n    slot_start     DATETIME NOT NULL,\n    CONSTRAINT uk_slot\n        UNIQUE (seat_id, slot_start)\n);\n\n// 예약 1건 = 시간당 슬롯 행 N개\nfor (int i = 0; i < hours; i++)\n    rows.add(new ReservationSlot(\n        reservation.getId(), seatId,\n        startAt.plusHours(i)));\ntry { slots.saveAllAndFlush(rows); }\ncatch (DataIntegrityViolationException e) {\n    throw new SeatTakenException(\n        \"이미 예약된 시간이 포함되어 있습니다\");\n}")
    slide("핵심 ② 포인트 이중 사용 방지", ["회원 행 비관적 락을 트랜잭션 첫 문장으로 + 격리 수준 READ COMMITTED", "잔액 2,000P로 5좌석 동시 예약 → 1건 성공, 잔액 0", "포인트 원장(쌓기만) + (예약, 사유) 유니크 → 환불·적립 중복 0건"],
          code="@Transactional\npublic Reservation reserve(...) {\n    // 트랜잭션의 첫 문장: 회원 행 잠금\n    Member member =\n        members.findByIdForUpdate(memberId)\n            .orElseThrow(...);\n    ...\n    points.use(memberId,\n        reservation.getId(), price, now);\n}\n\n-- 같은 예약에 같은 사유는 한 번만\nCREATE UNIQUE INDEX uk_pl_once\n    ON point_ledger (reservation_id, reason);")
    slide("예약 이후 흐름", ["QR + 6자리 코드로 입실 · 키오스크 시뮬레이터", "이용 중 +1/+2시간 연장 · 시작 60분 전까지 전액 환불 취소", "노쇼·자동 종료는 1분마다 점검(멱등)"], "ticket-qr.png")
    slide("관리자 · 혼잡도 통계", ["지점 실시간 현황 · 구역별 시간별/7일 그래프", "매시 5분 집계 + 꺼져 있던 구간 보충(시간별 48시간 → 일별 7일)", "시·도별 현황, 좌석 켜기/끄기, 예약 강제 취소"], "admin-dashboard.png")
    slide("데이터와 규모", ["전국 256개 시·군·구 모두 최소 1곳, 실제 읍·면·동 위에 가상 지점 약 720곳", "좌석 약 1만 5천 석, 데모 예약 약 1.9만 건 자동 생성", "ERD 14개 테이블 · Flyway V1~V6"], "diagram_erd.png", "자세한 컬럼은 14_데이터_테이블_명세서.xlsx")
    slide("구성", ["브라우저(Thymeleaf·Leaflet·Chart.js) ↔ Spring MVC·Security ↔ 서비스 ↔ JPA/JDBC ↔ H2(MySQL 모드)/MySQL 8", "스케줄러: 노쇼·자동 종료 1분, 집계 매시 5분", "시간은 Clock 주입 → 시험에서 시각 고정"], "diagram_arch.png", "자세한 그림은 12_시스템·서비스_구성도.pdf, 13_ERD_설계결정.pdf", pic_w=6.4)
    slide("검증", [TEST_LINE + " (H2 MySQL 모드)", "락을 일부러 빼면 동시 예약 시험이 실패함을 확인(변형 시험)", "화면·HTTP는 실행 중인 앱에서 직접 확인하며 버그 발견·수정(키오스크 CSRF 누락, 도면 겹침 등)"])
    slide("아쉬운 점 · 앞으로", ["MySQL 8 실서버 · 모바일 폭 · 웹 계층 자동 시험은 아직 확인하지 못함", "결제·키오스크는 데모(실제 PG·QR 리더 없음)", "다음: MySQL 검증, Docker Compose, GitHub Actions, 시연 영상"], "points.png")
    slide("출처 · 크레딧", ["예약 뼈대 아이디어: chuyj15/OBM (코드는 사용하지 않음)", "행정구역 데이터: vuski/admdongkor · 지도: © OpenStreetMap contributors", "글꼴 Pretendard(OFL) · 아이콘 Lucide(ISC) · 감사합니다"])
    prs.save(OUT / "StudySeat_발표자료.pptx")
    print("PPTX saved")


if __name__ == "__main__":
    if not CHROME:
        sys.exit("Chrome을 찾지 못했어요.")
    OUT.mkdir(exist_ok=True)
    src = DOCS / "screenshots_new"
    if src.exists():
        SHOT.mkdir(exist_ok=True)
        for f in src.glob("*.png"):
            shutil.copy2(f, SHOT / f.name)
    for nm, svg_, w_, h_ in (("diagram_arch.png", ARCH, 940, 400), ("diagram_erd.png", erd_svg(), 900, 440)):   # PPT용 그림(구성도·ERD)
        f_ = OUT / "_svg.html"
        f_.write_text(f'<!doctype html><meta charset="utf-8"><body style="margin:0;background:#fff">{svg_}', encoding="utf-8")
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--window-size={w_ + 20},{h_ + 20}", f"--screenshot={SHOT / nm}", f_.as_uri()], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        f_.unlink()
    build_xlsx()
    build_pptx()
    build_pdfs()
    tmp = OUT / "_tmp.html"
    if tmp.exists():
        tmp.unlink()
