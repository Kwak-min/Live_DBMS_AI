#!/usr/bin/env python3
"""프론트 연동 직전 점검: 프론트가 실제로 부를 순서대로 REST 전 구간과 STOMP 구독을 확인한다.

docs/frontend-integration-guide.md의 화면별 흐름을 그대로 따라가며, 각 응답의 상태 코드와 핵심 필드를 검사한다.
RISK_ENABLED=true, REALTIME_ENABLED=true 인 로컬 백엔드와 docker-compose MariaDB(127.0.0.1:13306)를 전제로 한다.

  python scripts/frontend-contract-smoke.py [--out docs/evidence/frontend-smoke/<날짜>]

환경 변수: BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD (ADMIN 계정)
"""

import argparse
import datetime as dt
import importlib.util
import json
import os
import socket
import sys
import threading
import time

_spec = importlib.util.spec_from_file_location(
    "part_b", os.path.join(os.path.dirname(os.path.abspath(__file__)), "part-b-scenarios.py"))
pb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pb)

TARGET = {"host": "127.0.0.1", "port": 13306, "databaseName": "sample_app", "username": "monitor",
          "password": "monitor"}


class Smoke:
    def __init__(self, out):
        self.out = out
        os.makedirs(out, exist_ok=True)
        self.rows = []
        self.admin = pb.Client()

    def step(self, screen, name, ok, observed=""):
        self.rows.append({"screen": screen, "step": name, "ok": bool(ok), "observed": str(observed)})
        print(f"  [{'PASS' if ok else 'FAIL'}] {screen} | {name} -> {observed}")
        return ok

    def get(self, path):
        status, text, headers = self.admin.call("GET", path, token=self.admin.token)
        body = json.loads(text) if text and text.strip().startswith(("{", "[")) else None
        return status, body, headers

    def save(self, name, content):
        with open(os.path.join(self.out, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(content, ensure_ascii=False, indent=2))

    def run(self):
        email, password = os.environ["BOOTSTRAP_ADMIN_EMAIL"], os.environ["BOOTSTRAP_ADMIN_PASSWORD"]

        # 로그인 화면
        login = self.admin.login(email, password)
        self.step("로그인", "csrf → login 200, TokenResponse", login.get("tokenType") == "Bearer"
                  and login.get("expiresIn") == 900, f"role={login['user']['role']}")
        status, me, _ = self.get("/auth/me")
        self.step("로그인", "GET /auth/me", status == 200 and me["email"] == email.lower(), status)

        # DB 관리 화면
        name = "smoke-" + dt.datetime.now(dt.timezone.utc).strftime("%H%M%S")
        status, text, _ = self.admin.call("POST", "/databases", dict(TARGET, name=name, enabled=True),
                                          token=self.admin.token)
        target = json.loads(text) if status == 201 else {}
        self.step("DB 관리", "POST /databases 201 (응답에 계정 없음)",
                  status == 201 and "username" not in target and "password" not in target, status)
        db_id = target.get("id")

        # 실시간 구독(대시보드 진입 시 REST 기준선보다 먼저)
        stomp = pb.Stomp()
        stomp.send_frame("CONNECT", {"accept-version": "1.2", "heart-beat": "10000,10000",
                                     "Authorization": "Bearer " + self.admin.token})
        self.step("대시보드", "STOMP CONNECT", stomp.read(5) == "CONNECTED")
        # heart-beat:10000,10000을 약속했으므로 클라이언트도 10초 안에 한 번씩 heart-beat(개행)를 보낸다.
        # 보내지 않으면 서버가 연결을 끊는다(@stomp/stompjs 등 라이브러리는 자동 처리).
        stop = threading.Event()

        def heartbeat():
            while not stop.wait(8):
                try:
                    stomp.send_heartbeat()
                except OSError:
                    return
        threading.Thread(target=heartbeat, daemon=True).start()
        for sub_id, dest in (("errors", "/user/queue/errors"),
                             (f"metrics-{db_id}", f"/topic/databases/{db_id}/metrics"),
                             (f"status-{db_id}", f"/topic/databases/{db_id}/status"),
                             (f"incidents-{db_id}", f"/topic/databases/{db_id}/incidents")):
            stomp.send_frame("SUBSCRIBE", {"id": sub_id, "destination": dest, "ack": "auto"})
        frames = self.collect(stomp, 25)
        self.save("stomp-frames.json", frames)
        kinds = {f["destination"].rsplit("/", 1)[-1] for f in frames}
        self.step("대시보드", "STOMP metrics 프레임 수신(MetricUpdated)", "metrics" in kinds,
                  f"{len(frames)} frames {sorted(kinds)}")
        self.step("대시보드", "STOMP status 프레임 수신", "status" in kinds, sorted(kinds))
        metric_frames = [f for f in frames if f["destination"].endswith("/metrics")]
        if metric_frames:
            body = metric_frames[0]["body"]
            self.step("대시보드", "metric 프레임 형식(schemaVersion·eventId·eventType·data)",
                      body.get("schemaVersion") == 1 and body.get("eventType") == "MetricUpdated"
                      and body.get("eventId") and isinstance(body.get("data"), dict), body.get("eventType"))

        # 대시보드 REST 기준선
        status, latest, _ = self.get(f"/metrics/{db_id}/latest")
        self.step("대시보드", "GET /metrics/{id}/latest 200", status == 200 and latest["collectionStatus"] == "SUCCESS",
                  f"{status} {latest and latest['collectionStatus']}")
        status, recent, _ = self.get(f"/metrics/{db_id}/recent?limit=10")
        self.step("대시보드", "GET /metrics/{id}/recent 200 (최신순)", status == 200 and len(recent) >= 1,
                  f"{status} n={len(recent or [])}")
        end = dt.datetime.now(dt.timezone.utc)
        start = end - dt.timedelta(minutes=10)
        fmt = lambda t: t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"
        status, history, _ = self.get(f"/metrics/{db_id}/history?start={fmt(start)}&end={fmt(end)}")
        self.step("대시보드", "GET /metrics/{id}/history 200 (오래된 순)", status == 200 and len(history) >= 1,
                  f"{status} n={len(history or [])}")
        status, snap, _ = self.get(f"/databases/{db_id}/status")
        self.step("대시보드", "GET /databases/{id}/status 200 FRESH·UP", status == 200
                  and snap["dataFreshness"] == "FRESH" and snap["connectionStatus"] == "UP",
                  f"{status} {snap and (snap['connectionStatus'], snap['dataFreshness'], snap['riskLevel'])}")
        status, page, _ = self.get("/databases?page=0&size=20")
        row = next((d for d in (page or {}).get("items", []) if d["id"] == db_id), None)
        # RISK_ENABLED이면 A가 database_configs 표시 컬럼을 갱신하지 않으므로, 목록 값이 채워져 있고
        # 앞서 읽은 /status보다 늦거나 같으면 monitoring_states에서 읽은 것이다(사이에 수집이 더 일어날 수 있음).
        self.step("DB 관리", "GET /databases 목록 상태 = /status 상태(monitoring_states)",
                  status == 200 and row and snap and row["connectionStatus"] == snap["connectionStatus"]
                  and row["lastAttemptAt"] is not None and row["lastAttemptAt"] >= snap["lastAttemptAt"],
                  f"list={row and (row['connectionStatus'], row['lastAttemptAt'])} "
                  f"status={snap and (snap['connectionStatus'], snap['lastAttemptAt'])}")
        status, detail, _ = self.get(f"/databases/{db_id}")
        self.step("DB 관리", "GET /databases/{id} 200", status == 200 and detail["configVersion"] >= 1, status)
        status, text, _ = self.admin.call("POST", f"/databases/{db_id}/ping", token=self.admin.token)
        self.step("DB 관리", "POST /databases/{id}/ping 200 UP", status == 200 and json.loads(text)["status"] == "UP",
                  f"{status}")

        # 사건·정책 화면
        status, incidents, _ = self.get(f"/incidents?databaseConfigId={db_id}")
        self.step("사건", "GET /incidents 200 페이지", status == 200 and "items" in incidents, status)
        status, policy, _ = self.get(f"/databases/{db_id}/risk-policy")
        self.step("정책", "GET /databases/{id}/risk-policy 200 (기본 2규칙)",
                  status == 200 and len(policy["rules"]) == 2, f"{status} v{policy and policy['version']}")
        body = {k: policy[k] for k in ("version", "staleAfterSeconds", "notificationCooldownSeconds", "rules")}
        body["staleAfterSeconds"] = 60
        status, text, _ = self.admin.call("PUT", f"/databases/{db_id}/risk-policy", body, token=self.admin.token)
        self.step("정책", "PUT /databases/{id}/risk-policy 200 (version+1)",
                  status == 200 and json.loads(text)["version"] == policy["version"] + 1, status)
        status, text, _ = self.admin.call("PUT", f"/databases/{db_id}/risk-policy", body, token=self.admin.token)
        self.step("정책", "같은 version 재전송 409 POLICY_VERSION_CONFLICT",
                  status == 409 and pb.code_of(text) == "POLICY_VERSION_CONFLICT", status)

        # 알림 설정 화면
        status, cfg, _ = self.get("/notifications/push-config")
        self.step("알림", "GET /notifications/push-config (VAPID 설정 시 200, 없으면 503)", status in (200, 503),
                  f"{status}")
        for path in ("/notifications/push-subscriptions", "/notifications/webhooks?page=0&size=20",
                     "/notifications/deliveries?page=0&size=20"):
            status, _, _ = self.get(path)
            self.step("알림", f"GET {path.split('?')[0]} 200", status == 200, status)

        # 관리자 화면
        for path in ("/users?page=0&size=20", "/audit-logs?page=0&size=20", "/access-logs?page=0&size=20"):
            status, _, _ = self.get(path)
            self.step("관리자", f"GET {path.split('?')[0]} 200", status == 200, status)

        # AI 화면
        status, ai, _ = self.get("/ai/status")
        self.step("AI", "GET /ai/status 200", status == 200 and "available" in ai, f"{status} available={ai and ai['available']}")
        status, reports, _ = self.get(f"/ai/reports?databaseConfigId={db_id}")
        self.step("AI", "GET /ai/reports 200 페이지", status == 200 and "items" in reports, status)

        # 일시 중지 → 상태 이벤트 → 재개
        status, text, _ = self.admin.call("PATCH", f"/databases/{db_id}",
                                          {"configVersion": detail["configVersion"], "enabled": False},
                                          token=self.admin.token)
        paused = json.loads(text) if status == 200 else {}
        self.step("DB 관리", "PATCH enabled=false 200 (configVersion+1)",
                  status == 200 and paused["configVersion"] == detail["configVersion"] + 1, status)
        frames = self.collect(stomp, 6)
        status_frames = [f for f in frames if f["destination"].endswith("/status")]
        self.save("stomp-frames-after-pause.json", frames)
        self.step("대시보드", "일시 중지 후 status 프레임 PAUSED",
                  any(json.dumps(f["body"]).find("PAUSED") >= 0 for f in status_frames),
                  f"{len(status_frames)} status frames")
        status, snap, _ = self.get(f"/databases/{db_id}/status")
        self.step("대시보드", "GET /status PAUSED·riskLevel null",
                  status == 200 and snap["dataFreshness"] == "PAUSED" and snap["riskLevel"] is None, snap and snap["dataFreshness"])
        status, text, _ = self.admin.call("PATCH", f"/databases/{db_id}",
                                          {"configVersion": detail["configVersion"], "enabled": True},
                                          token=self.admin.token)
        self.step("DB 관리", "옛 configVersion으로 PATCH 409 CONFIG_VERSION_CONFLICT",
                  status == 409 and pb.code_of(text) == "CONFIG_VERSION_CONFLICT", status)
        status, _, _ = self.admin.call("DELETE", f"/databases/{db_id}", token=self.admin.token)
        self.step("DB 관리", "DELETE /databases/{id} 204", status == 204, status)
        status, _, _ = self.get(f"/databases/{db_id}/status")
        self.step("DB 관리", "삭제 후 /status 404", status == 404, status)
        stop.set()
        stomp.close()

        status, _, _ = self.admin.auth_post("/auth/logout")
        self.step("로그인", "POST /auth/logout 204", status == 204, status)
        self.save("summary.json", self.rows)
        failed = [r for r in self.rows if not r["ok"]]
        print(f"\n{len(self.rows) - len(failed)}/{len(self.rows)} PASS")
        return not failed

    @staticmethod
    def collect(stomp, seconds):
        frames, deadline = [], time.time() + seconds
        while time.time() < deadline:
            batch = Smoke.read_messages(stomp, max(0.1, deadline - time.time()))
            if batch is None:
                break
            frames.extend(batch)
        return frames

    @staticmethod
    def read_messages(stomp, timeout):
        """도착한 STOMP MESSAGE 프레임들을 [{destination, body}]로 돌려준다. 연결 종료면 None."""
        stomp.sock.settimeout(timeout)
        try:
            chunk = stomp.sock.recv(65536)
        except socket.timeout:
            return []
        except OSError:
            return None
        if not chunk:
            return None
        stomp.buffer += chunk
        messages = []
        # 서버→클라이언트 웹소켓 프레임은 마스킹이 없다. 완성된 프레임만 꺼내고 나머지는 버퍼에 남긴다.
        while len(stomp.buffer) >= 2:
            data = stomp.buffer
            length, offset = data[1] & 0x7F, 2
            if length == 126:
                length, offset = int.from_bytes(data[2:4], "big"), 4
            elif length == 127:
                length, offset = int.from_bytes(data[2:10], "big"), 10
            if len(data) < offset + length:
                break
            stomp.text = getattr(stomp, "text", "") + data[offset:offset + length].decode(errors="replace")
            stomp.buffer = data[offset + length:]
        while "\x00" in getattr(stomp, "text", ""):
            frame, stomp.text = stomp.text.split("\x00", 1)
            frame = frame.lstrip("\n")
            if not frame.startswith("MESSAGE"):
                continue
            head, _, body = frame.partition("\n\n")
            headers = dict(line.split(":", 1) for line in head.split("\n")[1:] if ":" in line)
            try:
                parsed = json.loads(body)
            except ValueError:
                parsed = body
            messages.append({"destination": headers.get("destination", ""), "body": parsed})
        return messages


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=os.path.join("docs", "evidence", "frontend-smoke", dt.date.today().isoformat()))
    args = parser.parse_args()
    sys.exit(0 if Smoke(args.out).run() else 1)


if __name__ == "__main__":
    main()
