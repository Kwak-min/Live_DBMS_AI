#!/usr/bin/env python3
"""B 담당 필수 통합 시나리오(T01~T05, T27, T28) 실제 환경 실행기.

docs/integration-handoff.md 5절의 기대 결과를 docker-compose 의존성(PostgreSQL·Redis)과
실행 중인 백엔드에 대해 확인하고, 요청·응답·DB 관측값을 증거 디렉터리에 남긴다.

사전 조건
  - `docker compose up -d` 로 의존성 실행, 백엔드는 local 프로필 + REALTIME_ENABLED=true 로 실행 중
  - ADMIN 계정: BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD
  - JWT_SIGNING_KEYS / JWT_ACTIVE_KID: 백엔드와 같은 값(T03에서 만료·변조 토큰을 만든다)
  - TRUSTED_PROXY_CIDRS 는 비워 둔다(T27)
  - T28: LEGACY_TIME_ZONE, DB_CONFIG_ENCRYPTION_KEYS, DB_CONFIG_ACTIVE_KEY_VERSION 과
    APP_MIGRATE_CMD(별도 DB 이름을 인자로 받아 백엔드를 한 번 실행해 Flyway를 적용하고 종료하는 셸 명령).
    없으면 T28을 보류로 남긴다.

실행
  python scripts/part-b-scenarios.py [--out docs/evidence/part-b/<날짜>]

주의: T05에서 Redis를 잠시 중지한다. 로컬 환경에서만 실행한다.
"""

import argparse
import base64
import concurrent.futures
import datetime as dt
import hashlib
import hmac
import http.cookiejar
import json
import os
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = os.environ.get("API_BASE", "http://localhost:8080/api/v1")
WS_HOST, WS_PORT = "localhost", int(os.environ.get("APP_PORT", "8080"))
ORIGIN = os.environ.get("PUBLIC_ORIGIN", "http://localhost:5173")
PG = os.environ.get("PG_CONTAINER", "live_dbms_ai-postgres-1")
REDIS = os.environ.get("REDIS_CONTAINER", "live_dbms_ai-redis-1")
SLACK_OK = "https://hooks.slack.com/services/T00000000/B00000000/" + "X" * 24


# ---------------------------------------------------------------- 공통 도구

def sh(args, check=True, input_text=None):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", input=input_text)
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(args[:4])} failed: {result.stderr.strip()}")
    return result.stdout


def psql(sql, db="monitoring_db"):
    out = sh(["docker", "exec", "-i", PG, "psql", "-U", "postgres", "-d", db,
              "-v", "ON_ERROR_STOP=1", "-tAX", "-F", "|", "-f", "-"], input_text=sql)
    return [line.split("|") for line in out.splitlines() if line != ""]


def psql_one(sql, db="monitoring_db"):
    rows = psql(sql, db)
    return rows[0][0] if rows else None


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def b64url_decode(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class Client:
    """쿠키를 가진 브라우저 한 개. CSRF·Origin은 기본으로 붙이고 시나리오에서 빼거나 바꾼다."""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None
        self.token = None

    def call(self, method, path, body=None, token=None, headers=None, origin=ORIGIN, extra_cookie=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(API + path, data=data, method=method)
        if origin is not None:
            req.add_header("Origin", origin)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        opener = self.opener
        if extra_cookie is not None:
            req.add_header("Cookie", extra_cookie)
            opener = urllib.request.build_opener()
        try:
            with opener.open(req, timeout=30) as res:
                return res.status, res.read().decode("utf-8"), dict(res.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8"), dict(e.headers)

    def fetch_csrf(self):
        status, text, _ = self.call("GET", "/auth/csrf")
        assert status == 200, (status, text)
        self.csrf = json.loads(text)["csrfToken"]
        return self.csrf

    def auth_post(self, path, body=None, csrf=True):
        if csrf and self.csrf is None:
            self.fetch_csrf()
        headers = {"X-CSRF-Token": self.csrf} if csrf else {}
        return self.call("POST", path, body, headers=headers)

    def login(self, email, password):
        self.fetch_csrf()
        status, text, _ = self.auth_post("/auth/login", {"email": email, "password": password})
        assert status == 200, (status, text)
        self.token = json.loads(text)["accessToken"]
        return json.loads(text)

    def cookie(self, name):
        for c in self.jar:
            if c.name == name:
                return c.value
        return None


def code_of(text):
    try:
        return json.loads(text).get("code")
    except (ValueError, AttributeError):
        return None


# ---------------------------------------------------------------- 최소 STOMP over WebSocket 클라이언트

class Stomp:
    """표준 라이브러리만으로 /ws에 접속한다. 텍스트 프레임만 다룬다.
    CONNECT 헤더는 C 규격(docs/part-c-realtime.md)대로 accept-version:1.2, heart-beat:10000,10000만 쓴다."""

    def __init__(self, origin=ORIGIN):
        self.sock = socket.create_connection((WS_HOST, WS_PORT), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (f"GET /ws HTTP/1.1\r\nHost: {WS_HOST}:{WS_PORT}\r\nUpgrade: websocket\r\n"
                   f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                   f"Sec-WebSocket-Protocol: v12.stomp\r\nOrigin: {origin}\r\n\r\n")
        self.sock.sendall(request.encode())
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            response += chunk
        self.handshake = response.split(b"\r\n", 1)[0].decode(errors="replace")
        self.buffer = response.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in response else b""

    def send_frame(self, command, headers, body=""):
        frame = command + "\n" + "".join(f"{k}:{v}\n" for k, v in headers.items()) + "\n" + body + "\x00"
        payload = frame.encode()
        mask = os.urandom(4)
        header = bytearray([0x81])
        if len(payload) < 126:
            header.append(0x80 | len(payload))
        else:
            header.append(0x80 | 126)
            header += len(payload).to_bytes(2, "big")
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + mask + masked)

    def read(self, timeout):
        """다음 STOMP 프레임 명령(CONNECTED/ERROR/MESSAGE) 또는 'CLOSED'/'TIMEOUT'."""
        self.sock.settimeout(timeout)
        try:
            while True:
                while len(self.buffer) < 2:
                    chunk = self.sock.recv(4096)
                    if not chunk:
                        return "CLOSED"
                    self.buffer += chunk
                opcode = self.buffer[0] & 0x0F
                length = self.buffer[1] & 0x7F
                offset = 2
                if length == 126:
                    length = int.from_bytes(self.buffer[2:4], "big"); offset = 4
                elif length == 127:
                    length = int.from_bytes(self.buffer[2:10], "big"); offset = 10
                while len(self.buffer) < offset + length:
                    chunk = self.sock.recv(4096)
                    if not chunk:
                        return "CLOSED"
                    self.buffer += chunk
                payload = self.buffer[offset:offset + length]
                self.buffer = self.buffer[offset + length:]
                if opcode == 0x8:
                    return "CLOSED"
                if opcode == 0x1:
                    text = payload.decode(errors="replace").lstrip("\n")
                    if text:
                        return text.split("\n", 1)[0]
        except socket.timeout:
            return "TIMEOUT"
        except OSError:
            return "CLOSED"

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------- 결과 기록

class Scenario:
    def __init__(self, sid, title):
        self.id, self.title = sid, title
        self.checks, self.notes, self.pending = [], [], []

    def check(self, description, ok, observed=""):
        self.checks.append({"check": description, "ok": bool(ok), "observed": str(observed)})
        print(f"  [{'PASS' if ok else 'FAIL'}] {self.id} {description} -> {observed}")
        return ok

    def note(self, text):
        self.notes.append(text)
        print(f"  [NOTE] {self.id} {text}")

    def pend(self, text):
        self.pending.append(text)
        print(f"  [PENDING] {self.id} {text}")

    @property
    def result(self):
        if any(not c["ok"] for c in self.checks):
            return "FAIL"
        if not self.checks:
            return "PENDING"
        return "PASS (일부 보류)" if self.pending else "PASS"


class Runner:
    def __init__(self, out_dir):
        self.out = out_dir
        os.makedirs(out_dir, exist_ok=True)
        self.scenarios = {}
        self.admin = Client()
        self.suffix = dt.datetime.now(dt.timezone.utc).strftime("%m%d%H%M%S")
        self.user_email = f"t01-{self.suffix}@example.test"
        self.user_password = "B-scenario-" + secrets.token_urlsafe(9)

    def save(self, name, content):
        if not isinstance(content, str):
            content = json.dumps(content, ensure_ascii=False, indent=2)
        for secret in (self.user_password, os.environ.get("BOOTSTRAP_ADMIN_PASSWORD", "\0")):
            content = content.replace(secret, "***")
        with open(os.path.join(self.out, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        return name

    def record(self, name, method, path, status, text, body=None):
        safe_body = None if body is None else {k: ("***" if "password" in k.lower() else v) for k, v in body.items()}
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            parsed = text
        if isinstance(parsed, dict) and "accessToken" in parsed:
            parsed = dict(parsed, accessToken="<redacted>")
        self.save(name, {"request": {"method": method, "path": path, "body": safe_body},
                         "response": {"status": status, "body": parsed}})

    def scenario(self, sid, title):
        s = Scenario(sid, title)
        self.scenarios[sid] = s
        print(f"\n== {sid} {title}")
        return s

    # ----- 준비

    def setup(self):
        email = os.environ.get("BOOTSTRAP_ADMIN_EMAIL")
        password = os.environ.get("BOOTSTRAP_ADMIN_PASSWORD")
        if not email or not password:
            sys.exit("BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD 환경 변수가 필요합니다.")
        self.admin.login(email, password)

    # ----- T01

    def t01_signup(self):
        s = self.scenario("T01", "일반 가입에 role=ADMIN 주입")
        c = Client()
        c.fetch_csrf()
        body = {"email": self.user_email, "password": self.user_password, "displayName": "T01 user",
                "role": "ADMIN"}
        status, text, _ = c.auth_post("/auth/signup", body)
        self.record("T01-01-signup-with-role.json", "POST", "/auth/signup", status, text, body)
        s.check("role 필드를 넣은 가입은 400", status == 400, f"{status} {code_of(text)}")
        s.check("거절된 가입은 계정을 만들지 않음",
                psql_one(f"SELECT count(*) FROM users WHERE email = '{self.user_email}'") == "0")

        body.pop("role")
        status, text, _ = c.auth_post("/auth/signup", body)
        self.record("T01-02-signup.json", "POST", "/auth/signup", status, text, body)
        role = json.loads(text).get("role") if status == 201 else None
        s.check("정상 가입은 201, role=USER", status == 201 and role == "USER", f"{status} role={role}")
        s.check("가입은 토큰을 발급하지 않음", "accessToken" not in text)
        row = psql(f"SELECT role, password_hash FROM users WHERE email = '{self.user_email}'")[0]
        s.check("DB에는 Argon2id hash만 저장", row[1].startswith("$argon2id$") and self.user_password not in row[1],
                f"role={row[0]} hashPrefix={row[1][:30]}")
        self.save("T01-03-db-row.json", {"role": row[0], "passwordHashPrefix": row[1][:30],
                                         "containsPlaintext": self.user_password in row[1]})

    # ----- T02

    def t02_roles(self):
        s = self.scenario("T02", "로그인 후 일반 조회·관리 변경")
        user = Client()
        login = user.login(self.user_email, self.user_password)
        s.check("USER 로그인 role=USER", login["user"]["role"] == "USER")
        status, text, _ = user.call("GET", "/databases", token=user.token)
        self.record("T02-01-user-list.json", "GET", "/databases", status, text)
        s.check("USER 조회 200", status == 200, status)
        body = {"name": f"t02-{self.suffix}", "host": "127.0.0.1", "port": 13306, "databaseName": "sample_app",
                "username": "monitor", "password": "monitor", "enabled": False}
        status, text, _ = user.call("POST", "/databases", body, token=user.token)
        self.record("T02-02-user-create.json", "POST", "/databases", status, text, body)
        s.check("USER 변경 403 FORBIDDEN", status == 403 and code_of(text) == "FORBIDDEN", f"{status} {code_of(text)}")
        status, text, _ = user.call("GET", "/users", token=user.token)
        s.check("USER 사용자 관리 조회 403", status == 403, status)
        status, text, _ = self.admin.call("POST", "/databases", body, token=self.admin.token)
        self.record("T02-03-admin-create.json", "POST", "/databases", status, text, body)
        s.check("ADMIN 변경 201", status == 201, status)
        if status == 201:
            self.created_db = json.loads(text)["id"]

    # ----- T03

    def forge(self, token, **changes):
        """백엔드와 같은 키로 claim을 바꿔 다시 서명한다(만료 토큰 생성용)."""
        header_b64, payload_b64, _ = token.split(".")
        header = json.loads(b64url_decode(header_b64))
        payload = json.loads(b64url_decode(payload_b64))
        payload.update(changes)
        keys = json.loads(os.environ["JWT_SIGNING_KEYS"])
        secret = base64.b64decode(keys[header["kid"]])
        signing_input = b64url(json.dumps(header, separators=(",", ":")).encode()) + "." + \
            b64url(json.dumps(payload, separators=(",", ":")).encode())
        signature = hmac.new(secret, signing_input.encode(), hashlib.sha256).digest()
        return signing_input + "." + b64url(signature)

    def t03_tokens(self):
        s = self.scenario("T03", "만료/변조 JWT, 로그아웃된 sid 재사용")
        c = Client()
        c.login(self.user_email, self.user_password)
        status, _, _ = c.call("GET", "/auth/me", token=c.token)
        s.check("정상 토큰 200", status == 200, status)

        if os.environ.get("JWT_SIGNING_KEYS"):
            now = int(time.time())
            expired = self.forge(c.token, iat=now - 1000, exp=now - 60)
            status, text, _ = c.call("GET", "/auth/me", token=expired)
            self.record("T03-01-expired.json", "GET", "/auth/me", status, text)
            s.check("만료 토큰 401 ACCESS_TOKEN_EXPIRED", status == 401 and code_of(text) == "ACCESS_TOKEN_EXPIRED",
                    f"{status} {code_of(text)}")
            escalated = self.forge(c.token, role="ADMIN")
            status, text, _ = c.call("GET", "/users", token=escalated)
            s.check("claim만 ADMIN으로 바꾼 토큰은 권한 상승 불가(DB 기준 재확인)", status in (401, 403),
                    f"{status} {code_of(text)}")
        else:
            s.pend("JWT_SIGNING_KEYS 없음: 만료 토큰 생성 생략")

        head, payload, sig = c.token.split(".")
        tampered = head + "." + payload + "." + ("A" if sig[0] != "A" else "B") + sig[1:]
        status, text, _ = c.call("GET", "/auth/me", token=tampered)
        self.record("T03-02-tampered.json", "GET", "/auth/me", status, text)
        s.check("서명 변조 401 INVALID_TOKEN", status == 401 and code_of(text) == "INVALID_TOKEN",
                f"{status} {code_of(text)}")
        none_token = b64url(b'{"alg":"none","typ":"JWT"}') + "." + payload + "."
        status, text, _ = c.call("GET", "/auth/me", token=none_token)
        s.check("alg=none 401 INVALID_TOKEN", status == 401 and code_of(text) == "INVALID_TOKEN",
                f"{status} {code_of(text)}")

        stomp = Stomp()
        s.check("STOMP handshake 101", " 101 " in stomp.handshake, stomp.handshake)
        stomp.send_frame("CONNECT", {"accept-version": "1.2", "heart-beat": "10000,10000",
                                     "Authorization": "Bearer " + c.token})
        connected = stomp.read(5)
        s.check("유효 토큰 STOMP CONNECTED", connected == "CONNECTED", connected)

        status, text, _ = c.auth_post("/auth/logout")
        s.check("로그아웃 204", status == 204, status)
        started = time.time()
        after = stomp.read(15)
        waited = round(time.time() - started, 1)
        self.save("T03-03-stomp-after-logout.json", {"frameOrState": after, "secondsAfterLogout": waited})
        s.check("로그아웃한 세션의 STOMP 연결을 즉시(3초 안) 종료", after in ("ERROR", "CLOSED") and waited < 3,
                f"{after} after {waited}s")
        stomp.close()

        user_id = psql_one(f"SELECT id FROM users WHERE email = '{self.user_email}'")
        other = Client()
        other.login(self.user_email, self.user_password)
        stomp = Stomp()
        stomp.send_frame("CONNECT", {"accept-version": "1.2", "heart-beat": "10000,10000",
                                     "Authorization": "Bearer " + other.token})
        s.check("두 번째 로그인 STOMP CONNECTED", stomp.read(5) == "CONNECTED")
        status, _, _ = self.admin.call("PATCH", f"/users/{user_id}/status", {"enabled": False}, token=self.admin.token)
        started = time.time()
        after = stomp.read(15)
        waited = round(time.time() - started, 1)
        self.save("T03-05-stomp-after-disable.json", {"patchStatus": status, "frameOrState": after,
                                                      "secondsAfterDisable": waited})
        s.check("관리자가 사용자를 비활성화하면 그 사용자의 STOMP 연결도 즉시 종료",
                status == 200 and after in ("ERROR", "CLOSED") and waited < 3, f"{status} {after} after {waited}s")
        stomp.close()
        status, _, _ = self.admin.call("PATCH", f"/users/{user_id}/status", {"enabled": True}, token=self.admin.token)
        s.check("사용자 재활성화 200(이후 시나리오용)", status == 200, status)

        status, text, _ = c.call("GET", "/auth/me", token=c.token)
        self.record("T03-04-revoked-sid.json", "GET", "/auth/me", status, text)
        s.check("로그아웃된 sid의 Access 401 SESSION_REVOKED", status == 401 and code_of(text) == "SESSION_REVOKED",
                f"{status} {code_of(text)}")
        stomp = Stomp()
        stomp.send_frame("CONNECT", {"accept-version": "1.2", "heart-beat": "10000,10000",
                                     "Authorization": "Bearer " + c.token})
        frame = stomp.read(5)
        s.check("폐기된 토큰의 STOMP CONNECT 거절", frame in ("ERROR", "CLOSED"), frame)
        stomp.close()

    # ----- T04

    def t04_refresh(self):
        s = self.scenario("T04", "Refresh 회전 후 이전 토큰 재사용, 동시 갱신")
        c = Client()
        c.login(self.user_email, self.user_password)
        first_refresh = c.cookie("refreshToken")
        csrf_session = c.cookie("csrfSession")
        status, text, _ = c.auth_post("/auth/refresh")
        self.record("T04-01-refresh.json", "POST", "/auth/refresh", status, text)
        s.check("Refresh 200, 쿠키 회전", status == 200 and c.cookie("refreshToken") not in (None, first_refresh),
                status)
        rotated_access = json.loads(text)["accessToken"] if status == 200 else None
        sid_rows = psql(f"""SELECT s.sid, s.revoked_at IS NOT NULL FROM auth_sessions s JOIN users u ON u.id = s.user_id
                            WHERE u.email = '{self.user_email}' ORDER BY s.created_at DESC LIMIT 1""")
        sid = sid_rows[0][0]

        status, text, _ = c.call("POST", "/auth/refresh", headers={"X-CSRF-Token": c.csrf},
                                 extra_cookie=f"refreshToken={first_refresh}; csrfSession={csrf_session}")
        self.record("T04-02-reuse-old-refresh.json", "POST", "/auth/refresh", status, text)
        s.check("이전 Refresh 재사용 401 REFRESH_TOKEN_INVALID",
                status == 401 and code_of(text) == "REFRESH_TOKEN_INVALID", f"{status} {code_of(text)}")
        revoked = psql_one(f"SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE sid = '{sid}'")
        s.check("재사용 감지 시 sid 전체 폐기", revoked == "t", revoked)
        status, text, _ = c.call("GET", "/auth/me", token=rotated_access)
        s.check("회전으로 받은 Access도 SESSION_REVOKED", status == 401 and code_of(text) == "SESSION_REVOKED",
                f"{status} {code_of(text)}")

        d = Client()
        d.login(self.user_email, self.user_password)
        refresh, csrf_cookie, csrf = d.cookie("refreshToken"), d.cookie("csrfSession"), d.csrf

        def race(_):
            return d.call("POST", "/auth/refresh", headers={"X-CSRF-Token": csrf},
                          extra_cookie=f"refreshToken={refresh}; csrfSession={csrf_cookie}")[:2]
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results = list(pool.map(race, range(2)))
        statuses = sorted(r[0] for r in results)
        self.save("T04-03-concurrent-same-refresh.json",
                  [{"status": st, "code": code_of(tx)} for st, tx in results])
        s.check("같은 Refresh 동시 2건: 정확히 1건만 회전(200), 나머지는 재사용으로 401", statuses == [200, 401],
                statuses)
        s.note("브라우저 다중 탭 직렬화(Web Locks·BroadcastChannel)는 프론트 담당이며 여기서는 서버의 행 잠금만 확인한다.")

    # ----- T05

    def t05_csrf_origin_redis(self):
        s = self.scenario("T05", "CSRF/Origin 누락·변조, Redis 인증 저장소 중단")
        body = {"email": self.user_email, "password": self.user_password}
        c = Client()
        c.fetch_csrf()
        status, text, _ = c.call("POST", "/auth/login", body)
        self.record("T05-01-no-csrf.json", "POST", "/auth/login", status, text, body)
        s.check("CSRF 헤더 누락 403 CSRF_INVALID", status == 403 and code_of(text) == "CSRF_INVALID",
                f"{status} {code_of(text)}")
        status, text, _ = c.call("POST", "/auth/login", body, headers={"X-CSRF-Token": "x" + c.csrf[1:]})
        s.check("CSRF 값 변조 403 CSRF_INVALID", status == 403 and code_of(text) == "CSRF_INVALID",
                f"{status} {code_of(text)}")
        status, text, _ = c.call("POST", "/auth/login", body, headers={"X-CSRF-Token": c.csrf},
                                 origin="https://evil.example")
        self.record("T05-02-bad-origin.json", "POST", "/auth/login", status, text, body)
        s.check("다른 Origin 403 ORIGIN_NOT_ALLOWED", status == 403 and code_of(text) == "ORIGIN_NOT_ALLOWED",
                f"{status} {code_of(text)}")
        status, text, _ = c.call("POST", "/auth/login", body, headers={"X-CSRF-Token": c.csrf}, origin=None)
        s.check("Origin·Referer 모두 없음 403 ORIGIN_NOT_ALLOWED",
                status == 403 and code_of(text) == "ORIGIN_NOT_ALLOWED", f"{status} {code_of(text)}")
        status, _, _ = c.call("POST", "/auth/login", body, headers={"X-CSRF-Token": c.csrf})
        s.check("올바른 CSRF·Origin은 200", status == 200, status)

        sh(["docker", "stop", REDIS])
        try:
            time.sleep(2)
            r = Client()
            status, text, _ = r.call("GET", "/auth/csrf")
            self.record("T05-03-csrf-redis-down.json", "GET", "/auth/csrf", status, text)
            s.check("Redis 중단 중 CSRF 발급 503", status == 503, f"{status} {code_of(text)}")
            status, text, _ = c.call("POST", "/auth/login", body, headers={"X-CSRF-Token": c.csrf})
            self.record("T05-04-login-redis-down.json", "POST", "/auth/login", status, text, body)
            s.check("Redis 중단 중 로그인 503(보호 생략 없음)", status == 503 and code_of(text) == "DEPENDENCY_UNAVAILABLE",
                    f"{status} {code_of(text)}")
        finally:
            sh(["docker", "start", REDIS])
            time.sleep(3)
        status, _, _ = Client().call("GET", "/auth/csrf")
        s.check("Redis 복구 후 CSRF 발급 200", status == 200, status)

    # ----- T27

    def t27_addresses(self):
        s = self.scenario("T27", "임의 전달 IP 헤더·허용 밖 DB/Push/Webhook 주소")
        spoofed = "203.0.113.77"
        marker = f"t27-{self.suffix}"
        body = {"name": marker, "host": "127.0.0.1", "port": 13306, "databaseName": "sample_app",
                "username": "monitor", "password": "monitor", "enabled": False}
        status, text, _ = self.admin.call("POST", "/databases", body, token=self.admin.token,
                                          headers={"X-Forwarded-For": spoofed, "X-Real-IP": spoofed})
        ip = psql_one(f"""SELECT client_ip FROM audit_logs WHERE action = 'DATABASE_CREATED'
                          AND target_id = '{json.loads(text).get('id') if status == 201 else -1}'""")
        self.save("T27-01-forwarded-for.json", {"status": status, "sentXForwardedFor": spoofed, "auditClientIp": ip})
        s.check("신뢰 proxy가 없으면 X-Forwarded-For를 무시하고 실제 접속 IP 기록",
                ip is not None and ip != spoofed, f"audit client_ip={ip}")

        for name, host, port in (("cidr", "10.20.30.40", 13306), ("metadata", "169.254.169.254", 13306),
                                 ("port", "127.0.0.1", 5432)):
            bad = dict(body, name=f"{marker}-{name}", host=host, port=port)
            status, text, _ = self.admin.call("POST", "/databases", bad, token=self.admin.token)
            self.record(f"T27-02-db-{name}.json", "POST", "/databases", status, text, bad)
            s.check(f"허용 밖 DB 주소({host}:{port}) 거절", status in (400, 422) and status != 201,
                    f"{status} {code_of(text)}")

        for name, url in (("host", "https://example.com/services/T/B/X"),
                          ("query", SLACK_OK + "?x=1"), ("port", SLACK_OK.replace("hooks.slack.com", "hooks.slack.com:8443")),
                          ("http", SLACK_OK.replace("https://", "http://"))):
            hook = {"name": f"{marker}-{name}", "provider": "SLACK", "url": url, "enabled": True}
            status, text, _ = self.admin.call("POST", "/notifications/webhooks", hook, token=self.admin.token)
            self.record(f"T27-03-webhook-{name}.json", "POST", "/notifications/webhooks", status,
                        text, dict(hook, url="<redacted>"))
            s.check(f"허용 밖 Slack URL({name}) 거절", status == 400, f"{status} {code_of(text)}")

        p256dh = b64url(b"\x04" + os.urandom(64))
        auth = b64url(os.urandom(16))
        for name, endpoint in (("host", "https://push.evil.example/send/abc"),
                               ("http", "http://fcm.googleapis.com/fcm/send/abc"),
                               ("loopback", "https://127.0.0.1/fcm/send/abc"),
                               ("suffix", "https://evilfcm.googleapis.com.attacker.example/x")):
            push = {"endpoint": endpoint, "expirationTime": None, "keys": {"p256dh": p256dh, "auth": auth}}
            status, text, _ = self.admin.call("POST", "/notifications/push-subscriptions", push,
                                              token=self.admin.token)
            self.record(f"T27-04-push-{name}.json", "POST", "/notifications/push-subscriptions", status, text,
                        {"endpoint": endpoint})
            s.check(f"허용 밖 Push endpoint({name}) 거절", status == 400, f"{status} {code_of(text)}")

    # ----- T28

    def t28_legacy(self):
        s = self.scenario("T28", "baseline 기존 DB의 평문·시간·BLOCKED 데이터 이전")
        cmd = os.environ.get("APP_MIGRATE_CMD")
        if not cmd or not os.environ.get("LEGACY_TIME_ZONE"):
            s.pend("APP_MIGRATE_CMD 또는 LEGACY_TIME_ZONE 없음: 이전 시나리오 생략")
            return
        db = f"legacy_t28_{self.suffix}"
        psql(f"CREATE DATABASE {db}", db="postgres")
        v1 = open(os.path.join(os.path.dirname(__file__), "..", "backend", "src", "main", "resources", "db",
                               "migration", "V1__baseline_existing_schema.sql"), encoding="utf-8").read()
        psql(v1, db)
        psql("""
            CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50),
                description VARCHAR(200) NOT NULL, type VARCHAR(20) NOT NULL, script VARCHAR(1000) NOT NULL,
                checksum INT, installed_by VARCHAR(100) NOT NULL, installed_on TIMESTAMP NOT NULL DEFAULT now(),
                execution_time INT NOT NULL, success BOOLEAN NOT NULL);
            INSERT INTO flyway_schema_history VALUES (1, '1', '<< Flyway Baseline >>', 'BASELINE',
                '<< Flyway Baseline >>', NULL, 'postgres', now(), 0, true);
            INSERT INTO database_configs (collection_interval_seconds, created_at, updated_at, last_checked_at,
                enabled, host, name, password, port, status, username) VALUES
              (5, '2026-09-01 09:00:00', '2026-09-02 10:00:00', '2026-09-02 10:00:05', true, '127.0.0.1',
               'legacy-up', 'legacy-pass-1', 13306, 'UP', 'legacy-user-1'),
              (5, '2026-09-01 09:30:00', NULL, NULL, true, '127.0.0.1', 'legacy-blocked', 'legacy-pass-2',
               13306, 'BLOCKED', 'legacy-user-2'),
              (5, '2026-09-01 10:00:00', NULL, NULL, false, '127.0.0.1', 'legacy-disabled', 'legacy-pass-3',
               13306, 'UNKNOWN', 'legacy-user-3');
            INSERT INTO metric_data (collection_status, created_at, database_config_id, timestamp, active_connections)
              VALUES ('SUCCESS', '2026-09-02 10:00:05', 1, '2026-09-02 10:00:05', 7);
            INSERT INTO blocked_reasons (block_type, blocked_at, blocked_by, database_config_id, reason, severity)
              VALUES ('MANUAL', '2026-09-03 08:00:00', 'admin', 2, 'legacy block', 'CRITICAL');
            INSERT INTO audit_logs (client_ip, execution_time_ms, http_method, http_status, request_uri, timestamp)
              VALUES ('127.0.0.1', 12, 'GET', 200, '/api/legacy', '2026-09-03 08:00:00');
            """, db)
        before = {t: psql_one(f"SELECT count(*) FROM {t}", db)
                  for t in ("database_configs", "metric_data", "blocked_reasons", "audit_logs")}
        backup = sh(["docker", "exec", PG, "pg_dump", "-U", "postgres", "-d", db])
        self.save("T28-00-backup-size.json", {"bytes": len(backup), "tables": before})
        s.check("이전 전 백업(pg_dump) 생성", "CREATE TABLE public.database_configs" in backup, f"{len(backup)} bytes")

        started = time.time()
        result = subprocess.run(cmd + " " + db, shell=True, capture_output=True, text=True, encoding="utf-8")
        log = result.stdout + result.stderr
        self.save("T28-01-migrate-log-tail.txt", "\n".join(log.splitlines()[-40:]))
        s.check("백엔드 1회 실행으로 Flyway 이전 성공", result.returncode == 0 and "APPLICATION FAILED" not in log,
                f"exit={result.returncode} {round(time.time() - started)}s")
        s.check("이전 로그에 평문 계정·비밀번호 없음",
                not any(v in log for v in ("legacy-pass-1", "legacy-pass-2", "legacy-user-1")))

        versions = [r[0] for r in psql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", db)]
        s.check("V2~V7 마이그레이션 모두 적용", all(v in versions for v in ("2", "3", "4", "5", "6", "7")), versions)
        columns = [r[0] for r in psql("""SELECT column_name FROM information_schema.columns
                                         WHERE table_name = 'database_configs'""", db)]
        s.check("평문 username/password 컬럼 제거", "password" not in columns and "username" not in columns)
        encrypted = psql_one("""SELECT count(*) FROM database_configs WHERE username_ciphertext IS NOT NULL
                                AND password_ciphertext IS NOT NULL AND username_nonce IS NOT NULL
                                AND password_nonce IS NOT NULL""", db)
        s.check("모든 행 암호문 완성", encrypted == before["database_configs"], f"{encrypted}/{before['database_configs']}")
        dump_after = sh(["docker", "exec", PG, "pg_dump", "-U", "postgres", "-d", db, "--data-only"])
        s.check("DB 어디에도 평문 비밀번호 없음", "legacy-pass-1" not in dump_after and "legacy-pass-2" not in dump_after)
        after = {t: psql_one(f"SELECT count(*) FROM {t}", db) for t in ("database_configs", "metric_data",
                                                                     "blocked_reasons", "access_logs")}
        self.save("T28-02-counts.json", {"before": before, "after": after})
        s.check("행 수 보존(대상·메트릭·차단 이력·접속 로그)",
                after["database_configs"] == before["database_configs"] and after["metric_data"] == before["metric_data"]
                and after["blocked_reasons"] == before["blocked_reasons"] and after["access_logs"] == before["audit_logs"],
                after)
        times = psql("""SELECT name, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'), enabled
                        FROM database_configs ORDER BY id""", db)
        metric_time = psql_one("SELECT to_char(timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM metric_data", db)
        blocked_time = psql_one("SELECT to_char(blocked_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM blocked_reasons", db)
        self.save("T28-03-times-and-states.json", {"targets": times, "metricUtc": metric_time,
                                                   "blockedUtc": blocked_time,
                                                   "legacyTimeZone": os.environ["LEGACY_TIME_ZONE"]})
        expected_created = (dt.datetime(2026, 9, 1, 9, 0) - self.zone_offset()).strftime("%Y-%m-%d %H:%M:%S")
        s.check("시간 의미 보존: 로컬 09:00 → 같은 순간 UTC", times[0][1] == expected_created,
                f"{times[0][1]} (expected {expected_created})")
        s.check("BLOCKED 대상은 비활성으로 이전(자동 활성화 없음)", times[1][2] == "f", times[1])
        s.check("비활성 대상은 비활성 유지", times[2][2] == "f", times[2])
        states = psql("SELECT database_config_id, data_freshness, enabled FROM monitoring_states ORDER BY 1", db)
        self.save("T28-04-monitoring-states.json", states)
        s.check("이전된 비활성 대상은 PAUSED", all(r[1] == "PAUSED" for r in states if r[2] == "f"), states)
        restore_db = db + "_restore"
        psql(f"CREATE DATABASE {restore_db}", db="postgres")
        sh(["docker", "exec", "-i", PG, "psql", "-U", "postgres", "-d", restore_db, "-q", "-v", "ON_ERROR_STOP=1"],
           input_text=backup)
        restored = psql_one("SELECT count(*) FROM database_configs WHERE password LIKE 'legacy-pass-%'", restore_db)
        s.check("백업으로 이전 전 상태 복구 가능", restored == before["database_configs"], restored)
        psql(f"DROP DATABASE {restore_db}", db="postgres")
        s.note(f"이전 검증 DB {db}는 남겨 둔다(수동 확인용). 필요 없으면 DROP DATABASE {db}.")

    @staticmethod
    def zone_offset():
        from zoneinfo import ZoneInfo
        zone = ZoneInfo(os.environ["LEGACY_TIME_ZONE"])
        return dt.datetime(2026, 9, 1, 9, 0, tzinfo=zone).utcoffset()

    # ----- 요약

    def summary(self):
        lines = ["| ID | 결과 | 확인 수 |", "| --- | --- | --- |"]
        for sid, s in self.scenarios.items():
            lines.append(f"| {sid} | {s.result} | {sum(c['ok'] for c in s.checks)}/{len(s.checks)} |")
        self.save("summary.json", {sid: {"title": s.title, "result": s.result, "checks": s.checks,
                                         "notes": s.notes, "pending": s.pending}
                                   for sid, s in self.scenarios.items()})
        print("\n" + "\n".join(lines))
        return all(s.result.startswith("PASS") for s in self.scenarios.values())


def main():
    parser = argparse.ArgumentParser()
    today = dt.date.today().isoformat()
    parser.add_argument("--out", default=os.path.join("docs", "evidence", "part-b", today))
    parser.add_argument("--only", nargs="*", help="실행할 시나리오 ID(T01 등)")
    args = parser.parse_args()
    runner = Runner(args.out)
    runner.setup()
    steps = [("T01", runner.t01_signup), ("T02", runner.t02_roles), ("T03", runner.t03_tokens),
             ("T04", runner.t04_refresh), ("T05", runner.t05_csrf_origin_redis), ("T27", runner.t27_addresses),
             ("T28", runner.t28_legacy)]
    for sid, step in steps:
        if args.only and sid not in args.only and sid not in ("T01",):
            continue
        try:
            step()
        except Exception as exc:  # 한 시나리오 실패가 나머지를 막지 않게 한다
            runner.scenarios.setdefault(sid, Scenario(sid, "실행 오류")).check("실행 중 예외 없음", False, repr(exc))
    sys.exit(0 if runner.summary() else 1)


if __name__ == "__main__":
    main()
