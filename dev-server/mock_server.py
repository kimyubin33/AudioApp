#!/usr/bin/env python3
"""개발용 HTTPS 목업 서버. docs/api-v1.md 의 목록·상세 API만 흉내 낸다.

사용법:  python dev-server/mock_server.py
  - 처음 실행하면 개발용 인증서를 만든다 (dev-server/certs/, Git에는 올라가지 않음).
  - 앱(debug)이 이 서버의 루트 인증서를 믿도록 android/app/src/debug/res/raw/dev_ca.pem 으로 복사한다.
  - 에뮬레이터에서는 https://10.0.2.2:8443 으로 접속한다. (PC에서는 https://localhost:8443)

개발 전용이다. 127.0.0.1 에만 열고, 인증 헤더 같은 요청 내용은 기록하지 않는다.
"""

import json
import shutil
import ssl
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HOST = "127.0.0.1"  # PC 안에서만 접속 가능 (에뮬레이터의 10.0.2.2 는 PC의 127.0.0.1 로 연결된다)
PORT = 8443

ROOT = Path(__file__).resolve().parent
CERTS = ROOT / "certs"
DEBUG_RAW = ROOT.parent / "android" / "app" / "src" / "debug" / "res" / "raw"

# 개발용 샘플 콘텐츠 (Media3 데모 공개 파일)
STREAM_BASE = "https://storage.googleapis.com/exoplayer-test-media-0"
CONTENTS = [
    {
        "id": "sample-1",
        "title": "샘플 오디오 하나",
        "creator_id": "creator_01",
        "kind": "audio",
        "duration_seconds": 200,
        "ai_generated": True,
        "ai_tool": "Suno",
        "stream_url": f"{STREAM_BASE}/play.mp3",
    },
    {
        "id": "sample-2",
        "title": "파리의 재즈",
        "creator_id": "creator_01",
        "kind": "audio",
        "duration_seconds": 200,
        "ai_generated": True,
        "ai_tool": None,
        "stream_url": f"{STREAM_BASE}/Jazz_In_Paris.mp3",
    },
    {
        "id": "sample-3",
        "title": "다른 크리에이터의 소리",
        "creator_id": "creator_02",
        "kind": "audio",
        "duration_seconds": 200,
        "ai_generated": True,
        "ai_tool": "Udio",
        "stream_url": f"{STREAM_BASE}/play.mp3",
    },
    {
        # 샘플 영상(Big Buck Bunny)은 실제로는 약 10분이다. 숏폼 규칙(최대 180초)을 통과시키려고 적은 개발용 값이다.
        "id": "sample-4",
        "title": "짧은 영상 샘플",
        "creator_id": "creator_01",
        "kind": "short_video",
        "duration_seconds": 180,
        "ai_generated": True,
        "ai_tool": None,
        "stream_url": f"{STREAM_BASE}/BigBuckBunny_320x180.mp4",
    },
]

# 개발용 로그인: 이 ID 토큰으로만 로그인할 수 있고, 발급되는 접근 토큰도 고정값이다.
# (실제 서버는 Google 공개키로 ID 토큰을 검증하고 사용자마다 다른 토큰을 발급한다)
DEV_ID_TOKEN = "dev-id-token"
DEV_ACCESS_TOKEN = "dev-access-token"
REPORT_REASONS = {"spam", "inappropriate", "copyright", "missing_ai_label", "other"}
ID_CHARS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")
MAX_BODY = 64 * 1024

# 서버를 다시 켜면 초기화되는 메모리 저장소
BLOCKED = set()
REPORTS = []


def valid_id(value):
    return isinstance(value, str) and 1 <= len(value) <= 64 and set(value) <= ID_CHARS


def summary(item):
    """목록에는 재생 주소(stream_url)를 넣지 않는다."""
    return {k: v for k, v in item.items() if k != "stream_url"}


def find_openssl():
    found = shutil.which("openssl")
    if found:
        return found
    for candidate in (
        r"C:\Program Files\Git\usr\bin\openssl.exe",
        r"C:\Program Files\Git\mingw64\bin\openssl.exe",
    ):
        if Path(candidate).exists():
            return candidate
    sys.exit("openssl 을 찾을 수 없습니다. Git for Windows 를 설치했는지 확인하세요.")


def run(args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def ensure_certs():
    """개발용 루트 CA 와 서버 인증서를 만든다. 이미 있으면 그대로 쓴다."""
    if (CERTS / "server.pem").exists() and (CERTS / "ca.pem").exists():
        return
    openssl = find_openssl()
    CERTS.mkdir(exist_ok=True)

    # 최소한의 설정 파일 (윈도우의 Git openssl 은 기본 설정 위치를 못 찾을 때가 있다)
    conf = CERTS / "openssl.cnf"
    conf.write_text("[req]\ndistinguished_name=dn\nprompt=no\n[dn]\nCN=dev\n", encoding="ascii")

    ext = CERTS / "server.ext"
    ext.write_text(
        "basicConstraints=CA:FALSE\n"
        "keyUsage=digitalSignature\n"
        "extendedKeyUsage=serverAuth\n"
        "subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost\n",
        encoding="ascii",
    )

    print("개발용 인증서를 만드는 중...")
    try:
        # 루트 CA
        run([openssl, "ecparam", "-genkey", "-name", "prime256v1", "-noout", "-out", str(CERTS / "ca.key")])
        run([
            openssl, "req", "-x509", "-new", "-config", str(conf), "-key", str(CERTS / "ca.key"),
            "-sha256", "-days", "365", "-subj", "/CN=AudioApp Dev CA",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            "-out", str(CERTS / "ca.pem"),
        ])
        # 서버 인증서 (10.0.2.2 / 127.0.0.1 / localhost 용)
        run([openssl, "ecparam", "-genkey", "-name", "prime256v1", "-noout", "-out", str(CERTS / "server.key")])
        run([
            openssl, "req", "-new", "-config", str(conf), "-key", str(CERTS / "server.key"),
            "-subj", "/CN=localhost", "-out", str(CERTS / "server.csr"),
        ])
        run([
            openssl, "x509", "-req", "-in", str(CERTS / "server.csr"),
            "-CA", str(CERTS / "ca.pem"), "-CAkey", str(CERTS / "ca.key"), "-CAcreateserial",
            "-sha256", "-days", "90", "-extfile", str(ext), "-out", str(CERTS / "server.pem"),
        ])
    except subprocess.CalledProcessError as e:
        sys.exit("인증서 생성 실패:\n" + e.stderr.decode(errors="replace"))


def install_dev_ca():
    """앱(debug)이 믿을 루트 인증서(공개 부분만)를 debug 리소스로 복사한다. release 앱에는 들어가지 않는다."""
    DEBUG_RAW.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(CERTS / "ca.pem", DEBUG_RAW / "dev_ca.pem")


class Handler(BaseHTTPRequestHandler):
    server_version = "AudioAppMock"
    sys_version = ""

    def log_message(self, fmt, *args):
        # 요청 내용(헤더·쿼리)은 남기지 않고 경로만 기록한다
        print(f"{self.command} {urlparse(self.path).path} -> {args[1] if len(args) > 1 else ''}")

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_empty(self, status):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def is_authed(self):
        return self.headers.get("Authorization") == f"Bearer {DEV_ACCESS_TOKEN}"

    def read_json(self):
        """본문을 JSON 객체로 읽는다. 너무 크거나 올바르지 않으면 None."""
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= MAX_BODY:
                return None
            data = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None
        return data if isinstance(data, dict) else None

    def do_POST(self):
        path = urlparse(self.path).path
        body = self.read_json()

        if path == "/v1/auth/google":
            if body is None or body.get("id_token") != DEV_ID_TOKEN:
                return self.send_json(401, {"error": "unauthorized"})
            return self.send_json(200, {"access_token": DEV_ACCESS_TOKEN, "expires_in_seconds": 3600})

        if path == "/v1/reports":
            if not self.is_authed():
                return self.send_json(401, {"error": "unauthorized"})
            detail = None if body is None else body.get("detail")
            valid = (
                body is not None
                and valid_id(body.get("content_id"))
                and body.get("reason") in REPORT_REASONS
                and (detail is None or (isinstance(detail, str) and 1 <= len(detail) <= 500))
                and not (body.get("reason") == "other" and not detail)
            )
            if not valid:
                return self.send_json(400, {"error": "invalid_request"})
            REPORTS.append((body["content_id"], body["reason"]))
            print(f"  신고 접수: {body['content_id']} / {body['reason']}  (총 {len(REPORTS)}건)")
            return self.send_empty(204)

        self.send_json(404, {"error": "not_found"})

    def do_PUT(self):
        path = urlparse(self.path).path
        if path.startswith("/v1/blocks/"):
            if not self.is_authed():
                return self.send_json(401, {"error": "unauthorized"})
            creator_id = path.removeprefix("/v1/blocks/")
            if not valid_id(creator_id):
                return self.send_json(400, {"error": "invalid_request"})
            BLOCKED.add(creator_id)  # 이미 차단 상태여도 204
            return self.send_empty(204)
        self.send_json(404, {"error": "not_found"})

    def do_DELETE(self):
        path = urlparse(self.path).path
        if path.startswith("/v1/blocks/"):
            if not self.is_authed():
                return self.send_json(401, {"error": "unauthorized"})
            creator_id = path.removeprefix("/v1/blocks/")
            if not valid_id(creator_id):
                return self.send_json(400, {"error": "invalid_request"})
            BLOCKED.discard(creator_id)
            return self.send_empty(204)
        self.send_json(404, {"error": "not_found"})

    def do_GET(self):
        url = urlparse(self.path)
        query = parse_qs(url.query)

        if url.path == "/v1/contents":
            try:
                limit = int(query.get("limit", ["20"])[0])
                cursor = query.get("cursor", [None])[0]
                start = 0 if cursor is None else int(cursor.removeprefix("p"))
            except ValueError:
                return self.send_json(400, {"error": "invalid_request"})
            if not 1 <= limit <= 50 or start < 0:
                return self.send_json(400, {"error": "invalid_request"})
            # 로그인한 사용자가 차단한 크리에이터의 콘텐츠는 목록에서 뺀다
            visible = [c for c in CONTENTS if not (self.is_authed() and c["creator_id"] in BLOCKED)]
            page = visible[start:start + limit]
            more = start + limit < len(visible)
            return self.send_json(200, {
                "items": [summary(c) for c in page],
                "next_cursor": f"p{start + limit}" if more else None,
            })

        if url.path == "/v1/blocks":
            if not self.is_authed():
                return self.send_json(401, {"error": "unauthorized"})
            return self.send_json(200, {"creator_ids": sorted(BLOCKED)})

        if url.path.startswith("/v1/contents/"):
            content_id = url.path.removeprefix("/v1/contents/")
            for item in CONTENTS:
                if item["id"] == content_id:
                    return self.send_json(200, item)
            return self.send_json(404, {"error": "not_found"})

        self.send_json(404, {"error": "not_found"})


def main():
    ensure_certs()
    install_dev_ca()

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(CERTS / "server.pem", CERTS / "server.key")

    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"목업 서버 실행 중: https://{HOST}:{PORT}  (에뮬레이터: https://10.0.2.2:{PORT})")
    print("끄려면 Ctrl+C")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n종료합니다.")


if __name__ == "__main__":
    main()
