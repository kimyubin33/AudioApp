#!/usr/bin/env python3
"""개발용 TLS 중계기: https://127.0.0.1:8443 (에뮬레이터는 https://10.0.2.2:8443) → http://127.0.0.1:8787 (server/ 의 `npm run dev`).

앱은 HTTPS만 쓰기 때문에, 로컬 Worker 서버(HTTP) 앞에 개발용 인증서로 TLS를 입혀 주는 용도다.
mock_server.py 와 같은 개발용 인증서를 쓰므로 앱 쪽 설정은 그대로다. 개발 전용이며, 요청 내용(헤더·본문)은 기록하지 않는다.

사용법:  (서버) cd server && npm run dev      (중계기) python dev-server/tls_proxy.py
"""

import http.client
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

from mock_server import CERTS, ensure_certs, install_dev_ca

LISTEN_HOST, LISTEN_PORT = "127.0.0.1", 8443
UPSTREAM_HOST, UPSTREAM_PORT = "127.0.0.1", 8787
MAX_BODY = 1024 * 1024
HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers",
              "transfer-encoding", "upgrade", "host", "content-length"}


class Proxy(BaseHTTPRequestHandler):
    server_version = "AudioAppDevProxy"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(f"{self.command} {urlparse(self.path).path} -> {args[1] if len(args) > 1 else ''}")

    def _forward(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            self.send_error(413)
            return
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        try:
            conn = http.client.HTTPConnection(UPSTREAM_HOST, UPSTREAM_PORT, timeout=30)
            conn.request(self.command, self.path, body=body, headers=headers)
            res = conn.getresponse()
            data = res.read()
        except OSError:
            self.send_error(502, "upstream not running (cd server && npm run dev)")
            return
        finally:
            try:
                conn.close()
            except Exception:
                pass
        self.send_response(res.status)
        for k, v in res.getheaders():
            if k.lower() not in HOP_BY_HOP:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_DELETE = _forward


def main():
    ensure_certs()
    install_dev_ca()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(CERTS / "server.pem", CERTS / "server.key")
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Proxy)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"TLS 중계기 실행 중: https://{LISTEN_HOST}:{LISTEN_PORT} -> http://{UPSTREAM_HOST}:{UPSTREAM_PORT}")
    print("끄려면 Ctrl+C")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n종료합니다.")


if __name__ == "__main__":
    main()
