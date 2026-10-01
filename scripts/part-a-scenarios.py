#!/usr/bin/env python3
"""A 담당 필수 통합 시나리오(T06~T11, T13, T26) 실제 환경 실행기.

docs/integration-handoff.md 5절의 기대 결과를 docker-compose 의존성(PostgreSQL·Redis·MariaDB)과
실행 중인 백엔드에 대해 확인하고, 요청·응답·DB/Redis 관측값을 증거 디렉터리에 남긴다.

사전 조건
  - `docker compose up -d` 로 의존성 실행, 백엔드는 local 프로필로 실행 중
  - 보관 정리(T26)를 확인하려면 백엔드를 APP_METRICS_RETENTIONCLEANUPCRON='*/15 * * * * *' 로 실행
  - ADMIN 계정: BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD 환경 변수
  - (선택) APP_RESTART_CMD: T13에서 Redis 중단 중 백엔드를 재시작하는 셸 명령. 없으면 재시작 단계를 생략한다.
  - (선택) APP_LOG: 백엔드 로그 파일 경로. 있으면 비밀 노출 검사에 포함한다.

실행
  python scripts/part-a-scenarios.py [--out docs/evidence/part-a/<날짜>]

주의: 수집 대상 MariaDB를 재시작·중지하고 Redis를 잠시 중지한다. 로컬 환경에서만 실행한다.
"""

import argparse
import datetime as dt
import http.cookiejar
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("API_BASE", "http://localhost:8080/api/v1")
HEALTH = os.environ.get("HEALTH_URL", "http://localhost:8080/actuator/health")
ORIGIN = os.environ.get("PUBLIC_ORIGIN", "http://localhost:5173")
PG = os.environ.get("PG_CONTAINER", "live_dbms_ai-postgres-1")
REDIS = os.environ.get("REDIS_CONTAINER", "live_dbms_ai-redis-1")
MARIADB = os.environ.get("MARIADB_CONTAINER", "live_dbms_ai-mariadb-target-1")
MARIADB_ROOT_PASSWORD = os.environ.get("MARIADB_ROOT_PASSWORD", "root")
TARGET_HOST = os.environ.get("TARGET_HOST", "127.0.0.1")
TARGET_PORT = int(os.environ.get("TARGET_PORT", "13306"))
INTERVAL = 5  # app.collector.fixed-rate-ms 기본값(초)


# ---------------------------------------------------------------- 공통 도구

def sh(args, check=True, input_text=None):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", input=input_text)
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(args[:4])} failed: {result.stderr.strip()}")
    return result.stdout


def psql(sql):
    """PostgreSQL 질의 결과를 행(list[str]) 목록으로 반환한다. 구분자는 '|'."""
    out = sh(["docker", "exec", "-i", PG, "psql", "-U", "postgres", "-d", "monitoring_db",
              "-v", "ON_ERROR_STOP=1", "-tAX", "-F", "|", "-f", "-"], input_text=sql)
    return [line.split("|") for line in out.splitlines() if line != ""]


def psql_one(sql):
    rows = psql(sql)
    return rows[0][0] if rows else None


def redis_cli(*args, check=True):
    return sh(["docker", "exec", REDIS, "redis-cli", *args], check=check)


def mariadb_root(sql):
    sh(["docker", "exec", MARIADB, "mariadb", "-uroot", f"-p{MARIADB_ROOT_PASSWORD}", "-e", sql])


def wait_until(fn, timeout, interval=1.0):
    deadline = time.time() + timeout
    while True:
        value = fn()
        if value:
            return value
        if time.time() >= deadline:
            return value
        time.sleep(interval)


def pg_now():
    return psql_one("SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')")


class Api:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.token = None

    def call(self, method, path, body=None, auth=True, headers=None, retry_auth=True):
        url = path if path.startswith("http") else API + path
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Origin", ORIGIN)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if auth and self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with self.opener.open(req, timeout=30) as res:
                status, text, res_headers = res.status, res.read().decode("utf-8"), dict(res.headers)
        except urllib.error.HTTPError as e:
            status, text, res_headers = e.code, e.read().decode("utf-8"), dict(e.headers)
        if status == 401 and auth and retry_auth:
            self.login()
            return self.call(method, path, body, auth, headers, retry_auth=False)
        return status, text, res_headers

    def login(self):
        email = os.environ.get("BOOTSTRAP_ADMIN_EMAIL")
        password = os.environ.get("BOOTSTRAP_ADMIN_PASSWORD")
        if not email or not password:
            sys.exit("BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD 환경 변수가 필요합니다.")
        status, text, _ = self.call("GET", "/auth/csrf", auth=False, retry_auth=False)
        if status != 200:
            sys.exit(f"CSRF 발급 실패: {status} {text}")
        csrf = json.loads(text)["csrfToken"]
        status, text, _ = self.call("POST", "/auth/login", {"email": email, "password": password}, auth=False,
                                    headers={"X-CSRF-Token": csrf}, retry_auth=False)
        if status != 200:
            sys.exit(f"로그인 실패: {status} {text}")
        body = json.loads(text)
        if body["user"]["role"] != "ADMIN":
            sys.exit("ADMIN 계정이 필요합니다.")
        self.token = body["accessToken"]


class Scenario:
    def __init__(self, sid, title):
        self.id, self.title = sid, title
        self.checks = []
        self.notes = []
        self.pending = []

    def check(self, description, ok, observed=""):
        self.checks.append({"check": description, "ok": bool(ok), "observed": str(observed)})
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {self.id} {description} -> {observed}")
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
        self.api = Api()
        self.scenarios = {}
        self.evidence_files = []
        suffix = dt.datetime.now(dt.timezone.utc).strftime("%m%d%H%M%S")
        self.suffix = suffix
        self.db_user = "u" + secrets.token_hex(5)
        self.db_password = "Pw-" + secrets.token_urlsafe(18)
        self.target_id = None
        self.empty_target_id = None
        self.config_version = None

    # ----- 증거 저장

    def save(self, name, content):
        path = os.path.join(self.out, name)
        if not isinstance(content, str):
            content = json.dumps(content, ensure_ascii=False, indent=2)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        self.evidence_files.append(path)
        return os.path.relpath(path, self.out)

    def request(self, name, method, path, body=None, headers=None):
        status, text, res_headers = self.api.call(method, path, body, headers=headers)
        record = {"request": {"method": method, "path": path, "body": self.redact(body)},
                  "response": {"status": status,
                               "headers": {k: v for k, v in res_headers.items()
                                           if k.lower() in ("retry-after", "x-request-id", "content-type")},
                               "body": self.parse(text)}}
        self.save(name, record)
        return status, self.parse(text)

    def redact(self, body):
        if not isinstance(body, dict):
            return body
        return {k: ("<redacted>" if k in ("username", "password") else v) for k, v in body.items()}

    @staticmethod
    def parse(text):
        if not text:
            return None
        try:
            return json.loads(text)
        except ValueError:
            return text

    def scenario(self, sid, title):
        s = Scenario(sid, title)
        self.scenarios[sid] = s
        print(f"\n== {sid} {title}")
        return s

    # ----- 대상 상태 조회

    def metrics_since(self, target_id, since_iso, extra=""):
        rows = psql(f"""
            SELECT id, config_version, to_char(timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
                   collection_status, coalesce(error_code, ''), coalesce(qps::text, ''),
                   coalesce(unavailable_metrics->>'qps', ''), coalesce(slow_queries::text, ''),
                   coalesce(slow_queries_delta::text, ''), coalesce(active_connections::text, '')
            FROM metric_data
            WHERE database_config_id = {int(target_id)} AND created_at > '{since_iso}'::timestamptz {extra}
            ORDER BY timestamp, id""")
        keys = ["id", "configVersion", "timestamp", "status", "errorCode", "qps", "qpsUnavailable",
                "slowQueries", "slowQueriesDelta", "activeConnections"]
        return [dict(zip(keys, r)) for r in rows]

    def wait_metric(self, target_id, since_iso, predicate=lambda m: True, timeout=INTERVAL * 4):
        def found():
            return [m for m in self.metrics_since(target_id, since_iso) if predicate(m)]
        return wait_until(found, timeout)

    def config_row(self, target_id):
        rows = psql(f"""
            SELECT status, coalesce(last_checked_at::text, ''), coalesce(last_success_at::text, ''),
                   coalesce(last_error_message, ''), config_version, enabled
            FROM database_configs WHERE id = {int(target_id)}""")
        keys = ["status", "lastCheckedAt", "lastSuccessAt", "lastErrorMessage", "configVersion", "enabled"]
        return dict(zip(keys, rows[0])) if rows else None

    def current_version(self, target_id):
        status, body = self.api.call("GET", f"/databases/{target_id}")[:2]
        return json.loads(body)["configVersion"]

    # ---------------------------------------------------------------- 시나리오

    def setup(self):
        print(f"증거 경로: {self.out}")
        self.api.login()
        status = urllib.request.urlopen(HEALTH, timeout=10).status
        if status != 200:
            sys.exit("백엔드 health 확인 실패")
        mariadb_root(f"CREATE USER '{self.db_user}'@'%' IDENTIFIED BY '{self.db_password}';"
                     f"GRANT PROCESS ON *.* TO '{self.db_user}'@'%';"
                     f"GRANT SELECT ON sample_app.* TO '{self.db_user}'@'%';")
        self.started_at = pg_now()
        self.redis_start_ms = int(redis_cli("TIME").split()[0]) * 1000
        self.sha = sh(["git", "rev-parse", "--short", "HEAD"]).strip()

    def t06_register(self):
        s = self.scenario("T06", "DB 등록 후 암호문 저장·비밀 미노출·A 접속 성공")
        status, body = self.request("T06-01-create.json", "POST", "/databases", {
            "name": f"scn-a-{self.suffix}", "host": TARGET_HOST, "port": TARGET_PORT,
            "databaseName": "sample_app", "username": self.db_user, "password": self.db_password})
        if not s.check("등록 201", status == 201, status):
            raise SystemExit("대상 등록 실패로 중단합니다.")
        self.target_id = body["id"]
        self.config_version = body["configVersion"]
        s.check("응답에 username/password 필드 없음",
                "username" not in body and "password" not in body, sorted(body.keys()))

        row = psql(f"""
            SELECT username_ciphertext IS NOT NULL AND password_ciphertext IS NOT NULL,
                   position(convert_to('{self.db_user}', 'UTF8') in username_ciphertext) = 0,
                   position(convert_to('{self.db_password}', 'UTF8') in password_ciphertext) = 0,
                   position('{self.db_user}' in row_to_json(d)::text) = 0
                       AND position('{self.db_password}' in row_to_json(d)::text) = 0,
                   username_key_version, password_key_version, octet_length(username_nonce),
                   octet_length(password_ciphertext)
            FROM database_configs d WHERE id = {self.target_id}""")[0]
        self.save("T06-02-db-row.json", {
            "sql": "database_configs 행의 암호문 존재·평문 포함 여부(값 자체는 저장하지 않음)",
            "ciphertextPresent": row[0], "usernameNotInCiphertext": row[1], "passwordNotInCiphertext": row[2],
            "plaintextNotInRow": row[3], "keyVersions": [row[4], row[5]], "nonceBytes": row[6],
            "passwordCiphertextBytes": row[7]})
        s.check("PostgreSQL에 암호문만 저장", row[0] == "t" and row[3] == "t", f"ciphertext={row[0]}, plaintextInRow={row[3] != 't'}")
        s.check("암호문에 평문 바이트 없음", row[1] == "t" and row[2] == "t", f"user={row[1]}, password={row[2]}")

        ok = self.wait_metric(self.target_id, self.started_at, lambda m: m["status"] == "SUCCESS")
        status, latest = self.request("T06-03-first-metric.json", "GET", f"/metrics/{self.target_id}/latest")
        s.check("A 수집기가 복호화 계정으로 접속 성공(SUCCESS 스냅샷)", bool(ok),
                f"latest={status} {latest.get('collectionStatus') if isinstance(latest, dict) else latest}")

    def t09_empty(self):
        s = self.scenario("T09", "최신 스냅샷 없음·대상 없음·빈 기간·잘못된 기간")
        status, body = self.request("T09-01-create-paused.json", "POST", "/databases", {
            "name": f"scn-empty-{self.suffix}", "host": TARGET_HOST, "port": TARGET_PORT,
            "databaseName": "sample_app", "username": self.db_user, "password": self.db_password,
            "enabled": False})
        s.check("수집 중단 상태로 등록 201", status == 201 and body.get("enabled") is False, status)
        self.empty_target_id = tid = body["id"]

        status, body = self.request("T09-02-latest-no-snapshot.json", "GET", f"/metrics/{tid}/latest")
        s.check("스냅샷 없음 → 204 빈 본문", status == 204 and body is None, status)
        status, body = self.request("T09-03-recent-empty.json", "GET", f"/metrics/{tid}/recent")
        s.check("recent 빈 결과 → 200 []", status == 200 and body == [], f"{status} {body}")
        status, body = self.request("T09-04-history-empty.json", "GET",
                                    f"/metrics/{tid}/history?start=2026-01-01T00:00:00.000Z&end=2026-01-01T01:00:00.000Z")
        s.check("빈 기간 history → 200 []", status == 200 and body == [], f"{status} {body}")

        missing = int(psql_one("SELECT coalesce(max(id), 0) + 100000 FROM database_configs"))
        for name, path in [("latest", f"/metrics/{missing}/latest"), ("recent", f"/metrics/{missing}/recent")]:
            status, body = self.request(f"T09-05-{name}-not-found.json", "GET", path)
            s.check(f"대상 없음 {name} → 404 DATABASE_NOT_FOUND",
                    status == 404 and body.get("code") == "DATABASE_NOT_FOUND", f"{status} {body and body.get('code')}")

        bad = [("start-equals-end", "start=2026-01-01T00:00:00.000Z&end=2026-01-01T00:00:00.000Z", "INVALID_TIME_RANGE"),
               ("over-24h", "start=2026-01-01T00:00:00.000Z&end=2026-01-02T00:00:00.001Z", "INVALID_TIME_RANGE"),
               ("non-utc", "start=2026-01-01T00:00:00&end=2026-01-01T01:00:00.000Z", "VALIDATION_ERROR"),
               ("missing-end", "start=2026-01-01T00:00:00.000Z", "VALIDATION_ERROR")]
        for i, (name, query, code) in enumerate(bad, start=6):
            status, body = self.request(f"T09-{i:02d}-history-{name}.json", "GET", f"/metrics/{tid}/history?{query}")
            s.check(f"잘못된 기간({name}) → 400 {code}", status == 400 and body.get("code") == code,
                    f"{status} {body and body.get('code')}")
        status, body = self.request("T09-10-recent-limit-0.json", "GET", f"/metrics/{tid}/recent?limit=0")
        s.check("recent limit=0 → 400", status == 400, f"{status} {body and body.get('code')}")

    def t10_half_open(self):
        s = self.scenario("T10", "history [t0, t2) 반개구간과 정렬")
        snaps = wait_until(lambda: (lambda m: m if len(m) >= 3 else None)(
            self.metrics_since(self.target_id, self.started_at)), INTERVAL * 6)
        if not s.check("연속 스냅샷 3건 확보", snaps and len(snaps) >= 3, len(snaps or [])):
            return
        t0, t1, t2 = snaps[0], snaps[1], snaps[2]
        status, body = self.request("T10-01-history-t0-t2.json", "GET",
                                    f"/metrics/{self.target_id}/history?start={t0['timestamp']}&end={t2['timestamp']}")
        ids = [m["id"] for m in body] if status == 200 else []
        s.check("[t0, t2) 는 t0·t1 만 반환", ids == [int(t0["id"]), int(t1["id"])],
                f"t0={t0['timestamp']} t1={t1['timestamp']} t2={t2['timestamp']} ids={ids}")
        stamps = [(m["timestamp"], m["id"]) for m in body] if status == 200 else []
        s.check("timestamp, id 오름차순", stamps == sorted(stamps), stamps)
        status, body = self.request("T10-02-history-t1-only.json", "GET",
                                    f"/metrics/{self.target_id}/history?start={t1['timestamp']}&end={t2['timestamp']}")
        s.check("[t1, t2) 는 t1 하나(시작 포함·끝 제외)", status == 200 and [m["id"] for m in body] == [int(t1["id"])],
                [m["id"] for m in body] if status == 200 else status)

    def t11_normal(self):
        s = self.scenario("T11", "첫 수집 warmup·정상 0·카운터 초기화·접속 오류")
        snaps = self.metrics_since(self.target_id, self.started_at)
        first, second = snaps[0], snaps[1]
        status, body = self.request("T11-01-recent-after-register.json", "GET",
                                    f"/metrics/{self.target_id}/recent?limit=3")
        s.check("첫 수집은 SUCCESS, qps null, unavailable=WARMUP",
                first["status"] == "SUCCESS" and first["qps"] == "" and first["qpsUnavailable"] == "WARMUP", first)
        s.check("두 번째 수집은 qps 계산", second["qps"] != "" and second["qpsUnavailable"] == "", second)
        zero = [m for m in snaps[1:] if m["slowQueriesDelta"] == "0"]
        s.check("실제 0 은 0 으로 유지(null·unavailable 아님)",
                bool(zero) and zero[0]["slowQueries"] != "", zero[0] if zero else snaps)
        self.t11 = s

    def t07_config_version(self):
        s = self.scenario("T07", "configVersion 경쟁·수집 중 변경")
        version = self.current_version(self.target_id)
        status, body = self.request("T07-01-patch-stale-version.json", "PATCH", f"/databases/{self.target_id}",
                                    {"configVersion": version + 5, "name": f"scn-a-{self.suffix}-stale"})
        s.check("다른 configVersion PATCH → 409", status == 409, f"{status} {body and body.get('code')}")

        # 수집이 진행되는 동안 변경한다. 변경 직전 주기에 시작한 결과는 저장 시 버전 재검증으로 폐기돼야 한다.
        self.wait_metric(self.target_id, pg_now(), timeout=INTERVAL * 3)
        status, body = self.request("T07-02-patch-name.json", "PATCH", f"/databases/{self.target_id}",
                                    {"configVersion": version, "name": f"scn-a-{self.suffix}-renamed"})
        s.check("현재 configVersion PATCH → 200, 버전 증가",
                status == 200 and body.get("configVersion") == version + 1, f"{status} {body and body.get('configVersion')}")
        changed_at = pg_now()
        new_version = version + 1
        after = self.wait_metric(self.target_id, changed_at, lambda m: int(m["configVersion"]) == new_version,
                                 timeout=INTERVAL * 4)
        time.sleep(INTERVAL * 2)
        stale_after = psql_one(f"""SELECT count(*) FROM metric_data WHERE database_config_id = {self.target_id}
                                   AND config_version < {new_version}
                                   AND created_at > '{changed_at}'::timestamptz""")
        s.check("변경 후 이전 버전 수집 결과 저장 0건", stale_after == "0", f"stale rows after change={stale_after}")
        first_new = after[0] if after else None
        s.check("새 버전 첫 수집은 WARMUP(이전 기준과 비교 안 함)",
                first_new and first_new["qpsUnavailable"] == "WARMUP", first_new)
        cfg = self.config_row(self.target_id)
        _, detail = self.request("T07-03-get-after-collection.json", "GET", f"/databases/{self.target_id}")
        s.check("수집기가 최신 이름·버전을 덮어쓰지 않음",
                detail.get("name") == f"scn-a-{self.suffix}-renamed" and detail.get("configVersion") == new_version,
                f"name={detail.get('name')} configVersion={detail.get('configVersion')} status={cfg['status']}")
        self.save("T07-04-db-observation.json", {"changedAt": changed_at, "staleRowsAfterChange": stale_after,
                                                  "firstNewVersionMetric": first_new, "config": cfg})

    def t08_paused_ping(self):
        s = self.scenario("T08", "비활성 대상 Ping·활성화")
        version = self.current_version(self.target_id)
        status, body = self.request("T08-01-pause.json", "PATCH", f"/databases/{self.target_id}",
                                    {"configVersion": version, "enabled": False})
        s.check("수집 중단 PATCH 200", status == 200 and body.get("enabled") is False, status)
        paused_at = pg_now()
        time.sleep(INTERVAL * 2 + 2)
        before = self.config_row(self.target_id)
        count_before = psql_one(f"SELECT count(*) FROM metric_data WHERE database_config_id = {self.target_id}")
        outbox_before = psql_one(f"SELECT count(*) FROM event_outbox WHERE payload->>'databaseConfigId' = '{self.target_id}'")
        new_rows = self.metrics_since(self.target_id, paused_at)
        s.check("중단 후 정기 수집 결과 저장 0건", not new_rows, f"rows after pause={len(new_rows)}")

        status, body = self.request("T08-02-ping-paused.json", "POST", f"/databases/{self.target_id}/ping")
        s.check("중단 대상 Ping → 200 UP", status == 200 and body.get("status") == "UP",
                f"{status} {body and body.get('status')} version={body and body.get('version')}")
        status2, body2 = self.request("T08-03-ping-rate-limited.json", "POST", f"/databases/{self.target_id}/ping")
        s.check("10초 내 재Ping → 429 RATE_LIMITED", status2 == 429 and body2.get("code") == "RATE_LIMITED", status2)
        time.sleep(1)
        after = self.config_row(self.target_id)
        count_after = psql_one(f"SELECT count(*) FROM metric_data WHERE database_config_id = {self.target_id}")
        outbox_after = psql_one(f"SELECT count(*) FROM event_outbox WHERE payload->>'databaseConfigId' = '{self.target_id}'")
        s.check("Ping은 정기 상태·메트릭·이벤트를 바꾸지 않음",
                before == after and count_before == count_after and outbox_before == outbox_after,
                f"state same={before == after}, metrics {count_before}->{count_after}, outbox {outbox_before}->{outbox_after}")
        self.save("T08-04-state-before-after-ping.json", {"before": before, "after": after,
                                                          "metricRows": [count_before, count_after],
                                                          "outboxRows": [outbox_before, outbox_after]})

        version = self.current_version(self.target_id)
        status, body = self.request("T08-05-resume.json", "PATCH", f"/databases/{self.target_id}",
                                    {"configVersion": version, "enabled": True})
        resumed_at = pg_now()
        s.check("활성화 PATCH 200", status == 200 and body.get("enabled") is True, status)
        start = time.time()
        got = self.wait_metric(self.target_id, resumed_at, timeout=INTERVAL * 3)
        s.check("활성화 다음 주기에 수집 재개", bool(got),
                f"{round(time.time() - start, 1)}s 후 {got[0]['status'] if got else '없음'} "
                f"qpsUnavailable={got[0]['qpsUnavailable'] if got else ''}")

    def t11_failures(self):
        s = self.t11
        # 접속 오류(인증 실패)
        mariadb_root(f"ALTER USER '{self.db_user}'@'%' IDENTIFIED BY 'wrong-{secrets.token_hex(4)}';")
        since = pg_now()
        failed = self.wait_metric(self.target_id, since, lambda m: m["status"] != "SUCCESS", timeout=INTERVAL * 3)
        f = failed[0] if failed else {}
        s.check("인증 실패 → CONNECTION_FAILED / AUTH_FAILED, 모든 값 null",
                f.get("status") == "CONNECTION_FAILED" and f.get("errorCode") == "AUTH_FAILED"
                and f.get("activeConnections") == "" and f.get("qps") == "", f)
        mariadb_root(f"ALTER USER '{self.db_user}'@'%' IDENTIFIED BY '{self.db_password}';")
        since = pg_now()
        rec = self.wait_metric(self.target_id, since, lambda m: m["status"] == "SUCCESS", timeout=INTERVAL * 3)
        s.check("인증 복구 후 첫 성공은 WARMUP", rec and rec[0]["qpsUnavailable"] == "WARMUP", rec[0] if rec else None)

        # 접속 오류(대상 중지)
        sh(["docker", "stop", "-t", "1", MARIADB])
        since = pg_now()
        failed = self.wait_metric(self.target_id, since, lambda m: m["status"] != "SUCCESS", timeout=INTERVAL * 4)
        f = failed[0] if failed else {}
        s.check("대상 중지 → CONNECTION_FAILED / CONNECTION_REFUSED·CONNECT_TIMEOUT",
                f.get("status") == "CONNECTION_FAILED" and f.get("errorCode") in ("CONNECTION_REFUSED", "CONNECT_TIMEOUT"), f)
        self.request("T11-02-latest-connection-failed.json", "GET", f"/metrics/{self.target_id}/latest")
        sh(["docker", "start", MARIADB])
        wait_until(lambda: "healthy" in sh(["docker", "inspect", "-f", "{{.State.Health.Status}}", MARIADB]), 60, 2)
        # 실패 직후의 첫 성공을 본다(healthy 대기 중 이미 복구됐을 수 있다).
        rec = self.wait_metric(self.target_id, since, lambda m: m["status"] == "SUCCESS"
                               and int(m["id"]) > int(f.get("id", 0)), timeout=INTERVAL * 4)
        s.check("대상 복구 후 첫 성공은 WARMUP", rec and rec[0]["qpsUnavailable"] == "WARMUP", rec[0] if rec else None)

        # 카운터 초기화: 수집 직후 재시작해 다음 주기 전에 올라오면 COUNTER_RESET, 사이에 실패가 끼면 WARMUP
        self.wait_metric(self.target_id, pg_now(), lambda m: m["status"] == "SUCCESS", timeout=INTERVAL * 3)
        sh(["docker", "restart", "-t", "0", MARIADB])
        since = pg_now()
        rec = self.wait_metric(self.target_id, since, lambda m: m["status"] == "SUCCESS", timeout=INTERVAL * 6)
        between = [m for m in self.metrics_since(self.target_id, since) if rec and m["id"] < rec[0]["id"]]
        r = rec[0] if rec else {}
        s.check("재시작 뒤 첫 성공의 qps는 null(COUNTER_RESET 또는 실패 후 WARMUP), 음수 없음",
                r.get("qps") == "" and r.get("qpsUnavailable") in ("COUNTER_RESET", "WARMUP"),
                f"{r.get('qpsUnavailable')} (사이 실패 {len(between)}건)")
        negatives = psql_one(f"SELECT count(*) FROM metric_data WHERE database_config_id = {self.target_id} AND (qps < 0 OR slow_queries_delta < 0)")
        s.check("전 구간 음수 qps·slowQueriesDelta 없음", negatives == "0", negatives)
        self.save("T11-03-target-metrics.json", self.metrics_since(self.target_id, self.started_at))
        s.note("필수 조회 SQL 오류(PARTIAL_FAILURE/QUERY_FAILED)는 실제 MariaDB에서 재현하기 어려워 "
               "MetricSnapshotCalculatorTest로 검증한다.")

    def t13_outbox(self):
        s = self.scenario("T13", "PostgreSQL 성공 뒤 Redis 실패·publisher 재시작")
        sh(["docker", "stop", "-t", "1", REDIS])
        stopped_at = pg_now()
        time.sleep(INTERVAL * 3)
        stored = psql_one(f"SELECT count(*) FROM metric_data WHERE database_config_id = {self.target_id} "
                          f"AND created_at > '{stopped_at}'::timestamptz")
        s.check("Redis 중단 중에도 메트릭은 PostgreSQL에 저장", int(stored) > 0, f"rows={stored}")
        rows = psql(f"""SELECT event_id, payload->>'metricId', attempts, coalesce(left(last_error, 80), '')
                        FROM event_outbox WHERE event_type = 'MetricCollectedEvent'
                        AND payload->>'databaseConfigId' = '{self.target_id}'
                        AND created_at > '{stopped_at}'::timestamptz AND published_at IS NULL ORDER BY seq""")
        pending = [{"eventId": r[0], "metricId": r[1], "attempts": int(r[2]), "lastError": r[3]} for r in rows]
        s.check("발행 실패 이벤트가 outbox에 미발행으로 보존", len(pending) > 0, f"pending={len(pending)}")
        # 발행기는 실패한 첫 이벤트에서 주기를 멈추므로 attempts·lastError는 미발행 맨 앞 이벤트에 쌓인다.
        head = psql(f"""SELECT event_id, event_type, attempts, coalesce(left(last_error, 120), ''),
                                 to_char(next_attempt_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
                          FROM event_outbox WHERE published_at IS NULL
                          AND created_at > '{stopped_at}'::timestamptz - interval '10 seconds'
                          AND attempts > 0 ORDER BY seq LIMIT 1""")
        head = dict(zip(["eventId", "eventType", "attempts", "lastError", "nextAttemptAt"], head[0])) if head else None
        s.check("Redis 실패를 성공으로 처리하지 않음(attempts·lastError·backoff 기록)",
                head is not None and head["lastError"] != "", head)
        metric_ids = {p["metricId"] for p in pending}
        matched = psql_one(f"SELECT count(*) FROM metric_data WHERE id IN ({','.join(metric_ids) or '0'})")
        s.check("outbox metricId 가 실제 metric_data 행과 일치", int(matched) == len(metric_ids),
                f"{matched}/{len(metric_ids)}")
        self.save("T13-01-outbox-while-redis-down.json", {"redisStoppedAt": stopped_at, "failedHead": head,
                                                          "pending": pending})

        restart = os.environ.get("APP_RESTART_CMD")
        if restart:
            subprocess.run(restart, shell=True, check=True)
            s.note("Redis 중단 중 APP_RESTART_CMD로 백엔드를 재시작했다.")
        else:
            s.pend("APP_RESTART_CMD 미지정으로 publisher 프로세스 재시작은 생략(새 인스턴스 재발행은 OutboxPublisherIntegrationTest)")
        sh(["docker", "start", REDIS])
        wait_until(lambda: "PONG" in redis_cli("PING", check=False), 30)
        if restart:
            ok = wait_until(lambda: self.health_ok(), 120, 2)
            s.check("백엔드 재시작 후 health 200", ok, ok)
        ids = "','".join(p["eventId"] for p in pending)
        left = wait_until(lambda: psql_one(f"SELECT count(*) FROM event_outbox WHERE event_id IN ('{ids}') "
                                           f"AND published_at IS NULL") == "0" or None, 70, 2)
        s.check("Redis 복구 후 보존된 이벤트 전부 발행", bool(left), "published" if left else "still pending")

        stream = self.read_stream("stream:metrics", self.redis_start_ms)
        by_event = {}
        for payload in stream:
            by_event.setdefault(payload.get("eventId"), []).append(payload)
        same_ids = [p for p in pending if p["eventId"] in by_event]
        mismatched = [p for p in same_ids if any(str(x.get("metricId")) != p["metricId"] for x in by_event[p["eventId"]])]
        duplicated = [p["eventId"] for p in same_ids if len(by_event[p["eventId"]]) > 1]
        s.check("같은 eventId 로 재발행, metricId 가 DB·이벤트에서 일치",
                len(same_ids) == len(pending) and not mismatched,
                f"found={len(same_ids)}/{len(pending)}, metricId mismatch={len(mismatched)}, duplicates={len(duplicated)}")
        self.save("T13-02-redis-after-recovery.json", {
            "events": [{"eventId": p["eventId"], "metricId": p["metricId"],
                        "streamEntries": [{"eventId": x.get("eventId"), "metricId": x.get("metricId"),
                                           "publishedAt": x.get("publishedAt")} for x in by_event.get(p["eventId"], [])]}
                       for p in pending]})
        if restart:
            self.api.login()

    def health_ok(self):
        try:
            return urllib.request.urlopen(HEALTH, timeout=5).status == 200
        except Exception:
            return False

    def read_stream(self, key, start_ms):
        out = redis_cli("--raw", "XRANGE", key, f"{start_ms}-0", "+")
        payloads = []
        for line in out.splitlines():
            if line.startswith("{"):
                try:
                    payloads.append(json.loads(line))
                except ValueError:
                    pass
        return payloads

    def t26_retention(self):
        s = self.scenario("T26", "보관 경계 직전/직후 자료")
        days = int(os.environ.get("APP_METRICS_RETENTION_DAYS", "30"))
        rows = psql(f"""
            INSERT INTO metric_data (database_config_id, config_version, timestamp, collection_attempt_time,
                                     collection_status, unavailable_metrics, created_at)
            SELECT {self.target_id}, 1, ts, ts, 'SUCCESS', '{{}}'::jsonb, now()
            FROM (VALUES (now() - interval '{days} days' - interval '5 minutes'),
                         (now() - interval '{days} days' + interval '5 minutes')) AS v(ts)
            RETURNING id, to_char(timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')""")
        expired, kept = rows[0], rows[1]
        s.note(f"보관 {days}일 경계 기준 5분 전(id={expired[0]}, {expired[1]})·5분 후(id={kept[0]}, {kept[1]}) 행 삽입")
        gone = wait_until(lambda: psql_one(f"SELECT count(*) FROM metric_data WHERE id = {expired[0]}") == "0" or None,
                          45, 3)
        kept_left = psql_one(f"SELECT count(*) FROM metric_data WHERE id = {kept[0]}")
        recent_left = psql_one(f"SELECT count(*) FROM metric_data WHERE database_config_id = {self.target_id} "
                               f"AND timestamp > now() - interval '1 day'")
        s.check("경계 직전(만료) 메트릭 삭제", bool(gone),
                "deleted" if gone else "45초 내 삭제 안 됨 — APP_METRICS_RETENTIONCLEANUPCRON 설정 확인")
        s.check("경계 직후 메트릭과 최근 메트릭 보존", kept_left == "1" and int(recent_left) > 0,
                f"boundary-after={kept_left}, recent={recent_left}")
        self.save("T26-01-retention.json", {"retentionDays": days, "expired": expired, "kept": kept,
                                            "expiredDeleted": bool(gone), "keptRemaining": kept_left,
                                            "recentRemaining": recent_left})
        psql(f"DELETE FROM metric_data WHERE id = {kept[0]}")
        s.pend("오래된 OPEN 사건·근거 값 보존은 C의 incidents(V4) 구현 후 확인")

    def t07_delete(self):
        s = self.scenarios["T07"]
        status, _ = self.request("T07-05-delete.json", "DELETE", f"/databases/{self.target_id}")
        deleted_at = pg_now()
        s.check("수집 중 삭제 → 204", status == 204, status)
        time.sleep(INTERVAL * 2 + 2)
        after = psql_one(f"SELECT count(*) FROM metric_data WHERE database_config_id = {self.target_id} "
                         f"AND created_at > '{deleted_at}'::timestamptz")
        s.check("삭제 뒤 수집 결과 저장 0건", after == "0", f"rows after delete={after}")
        status, body = self.request("T07-06-latest-after-delete.json", "GET", f"/metrics/{self.target_id}/latest")
        s.check("삭제 대상 latest → 404", status == 404, f"{status} {body and body.get('code')}")

    def t06_leak_scan(self):
        s = self.scenarios["T06"]
        secrets_ = [self.db_user, self.db_password]
        found = {}

        def scan(source, text):
            hits = [x for x in secrets_ if x in text]
            if hits:
                found[source] = len(hits)

        for path in self.evidence_files:
            with open(path, encoding="utf-8") as f:
                scan(os.path.basename(path), f.read())
        scan("event_outbox", "\n".join(r[0] for r in psql(
            f"SELECT payload::text FROM event_outbox WHERE created_at >= '{self.started_at}'::timestamptz")))
        scan("audit_logs", "\n".join(r[0] for r in psql("SELECT row_to_json(a)::text FROM audit_logs a")))
        scan("access_logs", "\n".join(r[0] for r in psql("SELECT row_to_json(a)::text FROM access_logs a")))
        streams = {}
        for key in ("stream:metrics", "stream:statuses", "stream:collector-heartbeats"):
            text = redis_cli("--raw", "XRANGE", key, f"{self.redis_start_ms}-0", "+", check=False)
            streams[key] = text.count("eventId")
            scan(key, text)
        log = os.environ.get("APP_LOG")
        if log and os.path.exists(log):
            with open(log, encoding="utf-8", errors="replace") as f:
                scan("app-log", f.read())
        else:
            s.pend("APP_LOG 미지정으로 애플리케이션 로그 검사는 생략")
        self.save("T06-04-secret-scan.json", {
            "secrets": "등록한 MariaDB 계정명·비밀번호(값은 저장하지 않음)",
            "sources": ["evidence files", "event_outbox payload", "audit_logs", "access_logs",
                        *streams.keys(), "app-log" if log else None],
            "streamEntriesScanned": streams, "hits": found})
        s.check("API·이벤트·Redis·감사/접속 로그·앱 로그에 비밀 없음", not found, found or "0 hits")

    def cleanup(self):
        try:
            if self.empty_target_id:
                self.api.call("DELETE", f"/databases/{self.empty_target_id}")
            mariadb_root(f"DROP USER IF EXISTS '{self.db_user}'@'%';")
        except Exception as e:  # 정리 실패는 결과에 영향을 주지 않는다.
            print(f"cleanup warning: {e}")

    def report(self):
        finished = pg_now()
        lines = [f"# A 통합 시나리오 실행 결과", "",
                 f"- 실행: {self.started_at} ~ {finished} (UTC)",
                 f"- 기준 SHA: `{self.sha}`",
                 f"- 환경: docker-compose(PostgreSQL 16, Redis 7.4, MariaDB 10.11) + 로컬 백엔드, 수집 주기 {INTERVAL}초",
                 f"- 대상: 시나리오 전용 MariaDB 계정으로 등록한 `scn-a-{self.suffix}`(id={self.target_id}), "
                 f"`scn-empty-{self.suffix}`(id={self.empty_target_id})", "",
                 "| ID | 결과 | 확인 항목 | 보류 |", "| --- | --- | --- | --- |"]
        for s in self.scenarios.values():
            passed = sum(c["ok"] for c in s.checks)
            lines.append(f"| {s.id} | {s.result} | {passed}/{len(s.checks)} | {'; '.join(s.pending) or '-'} |")
        for s in self.scenarios.values():
            lines += ["", f"## {s.id} {s.title}", ""]
            for c in s.checks:
                lines.append(f"- {'PASS' if c['ok'] else 'FAIL'} {c['check']} — `{c['observed'][:300]}`")
            for n in s.notes:
                lines.append(f"- 참고: {n}")
            evidence = sorted(os.path.basename(p) for p in self.evidence_files if os.path.basename(p).startswith(s.id))
            if evidence:
                lines.append(f"- 증거: {', '.join(evidence)}")
        self.save("summary.md", "\n".join(lines) + "\n")
        self.save("results.json", {"startedAt": self.started_at, "finishedAt": finished, "sha": self.sha,
                                   "scenarios": [{"id": s.id, "title": s.title, "result": s.result,
                                                  "checks": s.checks, "notes": s.notes, "pending": s.pending}
                                                 for s in self.scenarios.values()]})
        print("\n" + "\n".join(lines[:8 + len(self.scenarios)]))
        return all(s.result != "FAIL" for s in self.scenarios.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=os.path.join(
        "docs", "evidence", "part-a", dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")))
    args = parser.parse_args()
    runner = Runner(args.out)
    runner.setup()
    try:
        runner.t06_register()
        runner.t09_empty()
        runner.t10_half_open()
        runner.t11_normal()
        runner.t07_config_version()
        runner.t08_paused_ping()
        runner.t11_failures()
        runner.t13_outbox()
        runner.t26_retention()
        runner.t07_delete()
        runner.t06_leak_scan()
    finally:
        runner.cleanup()
        ok = runner.report()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
