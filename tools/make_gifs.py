"""README용 시연 GIF 만들기 — 실행 중인 앱(http://localhost:8080)을 헤드리스 Chrome으로 눌러 가며 화면을 모아 GIF로 묶는다.

흐름: 홈 → 지점 찾기(지도) → 지점 상세 → 도면에서 좌석 선택 → 예약 → 예약 완료(QR) → 내 예약
데모 계정으로 로그인한 세션 쿠키를 브라우저에 넣어 쓴다. 실행: python tools/make_gifs.py [출력폴더]   (Pillow 필요)
"""
import base64
import io
import json
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shoot  # noqa: E402  (CDP 클라이언트·로그인·지점 찾기 재사용)
from PIL import Image  # noqa: E402

BASE = shoot.BASE


def main(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    op, sess = shoot.login("demo", "demo1234")
    daej = shoot.find_branch("대전 궁동")
    prof = tempfile.mkdtemp(prefix="ss_gif_")
    chrome = subprocess.Popen([shoot.CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={shoot.PORT}", f"--user-data-dir={prof}", "--remote-allow-origins=*", "about:blank"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    frames = []   # (PIL 이미지, 머무는 시간 ms)
    try:
        for _ in range(40):
            try:
                tabs = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{shoot.PORT}/json").read())
                break
            except Exception:  # noqa: BLE001
                time.sleep(.25)
        ws = shoot.WS(next(t for t in tabs if t["type"] == "page")["webSocketDebuggerUrl"])
        ws.call("Page.enable")
        ws.call("Network.enable")
        ws.call("Emulation.setDeviceMetricsOverride", {"width": 1280, "height": 760, "deviceScaleFactor": 1, "mobile": False})
        ws.call("Network.setCookie", {"name": "JSESSIONID", "value": sess, "url": BASE, "httpOnly": True})

        def go(path, wait=2.5):
            ws.call("Page.navigate", {"url": BASE + path})
            time.sleep(wait)

        def js(code):
            return ws.call("Runtime.evaluate", {"expression": code, "returnByValue": True}).get("result", {}).get("value")

        def snap(ms=1500):
            r = ws.call("Page.captureScreenshot", {"format": "png"})
            im = Image.open(io.BytesIO(base64.b64decode(r["data"]))).convert("RGB")
            im = im.resize((960, int(im.height * 960 / im.width)), Image.LANCZOS)
            frames.append((im, ms))

        go("/", 3)
        snap(1600)
        go("/branches", 6)
        snap(2200)
        go(f"/branches/{daej}", 3)
        snap(1600)
        now = time.localtime()
        start = min(21, now.tm_hour + 2)                      # 이미 지난 시간은 예약할 수 없으니 두 시간 뒤부터
        go(f"/branches/{daej}/book?date={time.strftime('%Y-%m-%d', now)}&start={start}&hours=2", 3)
        js("document.querySelector('.seat.FREE')?.scrollIntoView({block:'center'})")
        time.sleep(.6)
        snap(1500)
        js("document.querySelector('.seat.FREE')?.click()")
        time.sleep(.8)
        snap(1800)
        js("document.getElementById('reserveForm')?.requestSubmit()")
        time.sleep(3)
        snap(2400)
        go("/my/reservations", 2.5)
        snap(1800)
    finally:
        chrome.terminate()
    if len(frames) < 4:
        sys.exit("화면을 충분히 모으지 못했어요.")
    imgs = [f[0].quantize(colors=160, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE) for f in frames]
    target = out / "demo-booking.gif"
    imgs[0].save(target, save_all=True, append_images=imgs[1:], duration=[f[1] for f in frames], loop=0, optimize=True)
    print("저장:", target, round(target.stat().st_size / 1e6, 2), "MB,", len(frames), "장")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/images")
