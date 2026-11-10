"""화면 스크린샷 찍기 (실행 중인 앱 http://localhost:8080 을 헤드리스 Chrome으로).

로그인이 필요한 화면은 데모 계정(demo / admin — README 참고)으로 HTTP 로그인해 얻은 세션 쿠키를 브라우저에 넣어 찍는다.
Chrome 원격 제어(CDP)를 표준 라이브러리만으로 구현했다(외부 패키지 없음).
실행: python tools/shoot.py [출력폴더]   (앱이 켜져 있어야 함)
"""
import base64
import http.cookiejar
import json
import os
import re
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "http://localhost:8080"
CHROME = next((p for p in (r"C:\Program Files\Google\Chrome\Application\chrome.exe", r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe") if Path(p).exists()), None)
PORT = 9333


class WS:
    def __init__(self, url):
        u = urllib.parse.urlparse(url)
        self.s = socket.create_connection((u.hostname, u.port), timeout=60)
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.sendall((f"GET {u.path} HTTP/1.1\r\nHost: {u.hostname}:{u.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.s.recv(1)
        self.n = 0

    def _read(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c:
                raise ConnectionError("closed")
            b += c
        return b

    def _recv(self):
        data = b""
        while True:
            h = self._read(2)
            fin, ln = h[0] & 0x80, h[1] & 0x7F
            if ln == 126:
                ln = struct.unpack(">H", self._read(2))[0]
            elif ln == 127:
                ln = struct.unpack(">Q", self._read(8))[0]
            data += self._read(ln)
            if fin:
                return data

    def call(self, method, params=None):
        self.n += 1
        msg = json.dumps({"id": self.n, "method": method, "params": params or {}}).encode()
        mask = os.urandom(4)
        hdr = bytes([0x81])
        if len(msg) < 126:
            hdr += bytes([0x80 | len(msg)])
        elif len(msg) < 65536:
            hdr += bytes([0x80 | 126]) + struct.pack(">H", len(msg))
        else:
            hdr += bytes([0x80 | 127]) + struct.pack(">Q", len(msg))
        self.s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(msg)))
        while True:
            r = json.loads(self._recv())
            if r.get("id") == self.n:
                if "error" in r:
                    raise RuntimeError(r["error"])
                return r.get("result", {})


def login(user, pw):
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    page = op.open(BASE + "/login").read().decode()
    tok = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page) or re.search(r'value="([^"]+)"[^>]*name="_csrf"', page)
    data = {"username": user, "password": pw}
    if tok:
        data["_csrf"] = tok.group(1)
    op.open(urllib.request.Request(BASE + "/login", urllib.parse.urlencode(data).encode()))
    ck = {c.name: c.value for c in jar}
    if "JSESSIONID" not in ck:
        raise SystemExit(f"로그인 실패: {user}")
    return op, ck["JSESSIONID"]


def find_branch(name_part):
    for b in json.loads(urllib.request.urlopen(BASE + "/api/branches").read()):
        if name_part in b["name"]:
            return b["id"]
    raise SystemExit("지점을 못 찾음: " + name_part)


def make_reservation(op, branch_id):
    page = op.open(f"{BASE}/branches/{branch_id}/book").read().decode()
    tok = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page)
    f = {k: re.search(rf'name="{k}"[^>]*value="([^"]*)"', page) for k in ("date", "start", "hours")}
    seats = re.findall(r'class="seat[^"]*"[^>]*data-id="(\d+)"', page) or re.findall(r'data-id="(\d+)"', page)
    for sid in seats[:40]:
        data = {"seatId": sid, "branchId": branch_id, **{k: (v.group(1) if v else "") for k, v in f.items()}}
        if tok:
            data["_csrf"] = tok.group(1)
        try:
            r = op.open(urllib.request.Request(BASE + "/reserve", urllib.parse.urlencode(data).encode()))
            m = re.search(r"/reservations/(\d+)", r.geturl())
            if m:
                return m.group(1)
        except Exception:  # noqa: BLE001
            continue
    return None


def main(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    prof = tempfile.mkdtemp(prefix="ss_shoot_")
    chrome = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}", f"--user-data-dir={prof}", "--remote-allow-origins=*", "about:blank"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(40):
            try:
                tabs = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json").read())
                break
            except Exception:  # noqa: BLE001
                time.sleep(.25)
        ws = WS(next(t for t in tabs if t["type"] == "page")["webSocketDebuggerUrl"])
        ws.call("Page.enable")
        ws.call("Network.enable")
        ws.call("Emulation.setDeviceMetricsOverride", {"width": 1440, "height": 900, "deviceScaleFactor": 1, "mobile": False})

        def shot(name, path, wait=2.5, full=False, session=None):
            ws.call("Network.clearBrowserCookies")
            if session:
                ws.call("Network.setCookie", {"name": "JSESSIONID", "value": session, "url": BASE, "httpOnly": True})
            ws.call("Page.navigate", {"url": BASE + path})
            time.sleep(wait)
            if full:
                h = ws.call("Runtime.evaluate", {"expression": "Math.min(document.documentElement.scrollHeight, 2200)", "returnByValue": True})["result"]["value"]
                ws.call("Emulation.setDeviceMetricsOverride", {"width": 1440, "height": h, "deviceScaleFactor": 1, "mobile": False})
                time.sleep(.5)
            r = ws.call("Page.captureScreenshot", {"format": "png"})
            (out / f"{name}.png").write_bytes(base64.b64decode(r["data"]))
            if full:
                ws.call("Emulation.setDeviceMetricsOverride", {"width": 1440, "height": 900, "deviceScaleFactor": 1, "mobile": False})
            print("찍음:", name)

        daej, konk, rural = find_branch("대전 궁동"), find_branch("건대입구"), find_branch("대청면")
        shot("home", "/", full=True)
        shot("branches-map", "/branches", wait=6)
        shot("branch-detail", f"/branches/{daej}", full=True)
        shot("booking-daejeon", f"/branches/{daej}/book", full=True)
        shot("booking-konkuk", f"/branches/{konk}/book", full=True)
        shot("booking-rural", f"/branches/{rural}/book", full=True)
        shot("kiosk", "/kiosk")
        shot("login", "/login")
        shot("signup", "/signup")
        op, sess = login("demo", "demo1234")
        rid = make_reservation(op, daej)
        if rid:
            shot("ticket-qr", f"/reservations/{rid}", wait=3, full=True, session=sess)
        shot("my-reservations", "/my/reservations", session=sess, full=True)
        shot("points", "/my/points", session=sess, full=True)
        _, asess = login("admin", "admin1234")
        shot("admin-dashboard", "/admin", wait=5, session=asess, full=True)
    finally:
        chrome.terminate()


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/screenshots_new")
