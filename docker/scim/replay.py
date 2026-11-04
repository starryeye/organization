#!/usr/bin/env python3
"""SCIM 최초 싱크 요청을 실제 앱에 순차로 쏜다 — IdP 역할.

LDAP 은 원천 디렉터리를 띄우면 앱이 알아서 읽어 가지만, SCIM 은 우리가 받는 쪽이라
누군가 밖에서 보내 줘야 한다. 자동화 테스트는 그 역할을 테스트 코드가 하고, 이 스크립트는
사람이 실제 앱을 띄워 두고 만져 볼 때 쓴다.

**순차로 보낸다.** IdP 의 실제 프로비저닝이 그렇고, 동시에 쏘면 재려던 것 대신 동시성
처리를 재게 된다 — 그건 별도 시나리오(S3)의 몫이다.

서버가 id 를 발급하므로 POST 응답의 id 로 뒤 요청의 경로·멤버 값을 바꾼다. 시드의 생성 요청이
"차트아이디"(조직도 아이디)를 들고 있고, 스크립트가 그 아이디에 응답의 id 를 묶어 둔다.

**토큰을 받아 싣는다.** SCIM 엔드포인트는 bearer 토큰을 요구한다(설계 2026-10-28 §4). IdP 가 하듯 인가 서버(app-auth)의 토큰 엔드포인트에서
client credentials 로 받아 모든 요청에 싣는다 — 로컬 기본값은 개발용 클라이언트 `scim-idp`(비밀 `scim-idp-secret`)다. 범위를 요청하지 않으면
등록된 범위(scim)를 모두 받는다. 토큰은 60분짜리이고, 요청이 401 로 돌아오면 한 번 새로 받아 다시 보낸다.

  ./gradlew :connector-scim:generateScimSeed
  docker compose up -d openfga dynamodb-local
  ./gradlew :app-auth:bootRun
  ./gradlew :app-scim:bootRun
  python3 docker/scim/replay.py

다른 앱·인가 서버를 쓰려면 첫 인자로 앱 주소를 주고, 환경 변수로 토큰 엔드포인트와 클라이언트를 바꾼다:
  AUTH_TOKEN_URL(기본 http://localhost:9000/oauth2/token), SCIM_CLIENT_ID(scim-idp), SCIM_CLIENT_SECRET(scim-idp-secret)
"""
import base64
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8082"
SEED = pathlib.Path(__file__).with_name("initial-sync.ndjson")
TOKEN_URL = os.environ.get("AUTH_TOKEN_URL", "http://localhost:9000/oauth2/token")
CLIENT_ID = os.environ.get("SCIM_CLIENT_ID", "scim-idp")
CLIENT_SECRET = os.environ.get("SCIM_CLIENT_SECRET", "scim-idp-secret")


def fetch_token():
    """인가 서버에서 client credentials 로 액세스 토큰을 받는다(client_secret_basic). 못 받으면 이유를 적고 멈춘다."""
    basic = base64.b64encode(f"{CLIENT_ID}:{CLIENT_SECRET}".encode("utf-8")).decode("ascii")
    req = urllib.request.Request(
        TOKEN_URL,
        data=urllib.parse.urlencode({"grant_type": "client_credentials"}).encode("ascii"),
        method="POST",
        headers={"Authorization": f"Basic {basic}", "Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(req) as response:
            return json.loads(response.read())["access_token"]
    except urllib.error.HTTPError as e:
        sys.exit(f"토큰을 받지 못했다: {TOKEN_URL} → {e.code} (클라이언트 {CLIENT_ID} 의 id·비밀을 본다)")
    except (urllib.error.URLError, KeyError, ValueError) as e:
        sys.exit(f"토큰을 받지 못했다: {TOKEN_URL} → {e}\n  인가 서버가 떠 있는지 본다: ./gradlew :app-auth:bootRun")


# 지금 쓰는 토큰. 401 이 오면 새로 받는다
token = None


def translate(request, ids):
    """조직도 아이디로 쓰인 경로·조직 멤버 값을 서버가 발급한 id 로 바꾼다. 모르는 아이디는 그대로 둔다."""
    path = request["path"]
    for prefix in ("/scim/v2/Users/", "/scim/v2/Groups/"):
        if path.startswith(prefix):
            path = prefix + ids.get(path[len(prefix):], path[len(prefix):])
    body = request["body"]
    if isinstance(body, dict) and isinstance(body.get("members"), list):
        body = dict(body, members=[dict(m, value=ids.get(m["value"], m["value"])) for m in body["members"]])
    return dict(request, path=path, body=body)


def send(request, retry=True):
    """(상태코드, 응답 본문) 을 돌려준다. 본문이 JSON 이 아니면 None. 401 이면 토큰을 새로 받아 한 번 다시 보낸다."""
    global token
    if token is None:
        token = fetch_token()
    body = json.dumps(request["body"]).encode("utf-8")
    req = urllib.request.Request(
        BASE + request["path"],
        data=body,
        method=request["method"],
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(req) as response:
            return response.status, parse(response.read())
    except urllib.error.HTTPError as e:
        if e.code == 401 and retry:
            token = fetch_token()
            return send(request, retry=False)
        return e.code, parse(e.read())


def parse(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return None


def main():
    if not SEED.exists():
        sys.exit(f"시드가 없다: {SEED}\n  ./gradlew :connector-scim:generateScimSeed")

    requests = [json.loads(line) for line in SEED.read_text(encoding="utf-8").splitlines()]
    print(f"{len(requests)}건을 {BASE} 로 보낸다")

    실패 = []
    ids = {}  # 조직도 아이디 → 서버가 발급한 id
    시작 = time.time()
    for i, request in enumerate(requests, 1):
        request = translate(request, ids)
        status, 응답 = send(request)
        if status == 201 and request.get("차트아이디") and isinstance(응답, dict) and 응답.get("id"):
            ids[request["차트아이디"]] = 응답["id"]
        if status != 201:
            실패.append((request["설명"], status))
            if len(실패) <= 5:
                print(f"  실패: {request['설명']} → {status}")
        if i % 1000 == 0:
            print(f"  {i}건 / {time.time() - 시작:.1f}초")

    소요 = time.time() - 시작
    print(f"\n{len(requests)}건 / {소요:.1f}초 (건당 {소요 / len(requests) * 1000:.1f}ms)"
          f" / 실패 {len(실패)}건")
    return 1 if 실패 else 0


if __name__ == "__main__":
    sys.exit(main())
