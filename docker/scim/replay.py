#!/usr/bin/env python3
"""SCIM 최초 싱크 요청을 실제 앱에 순차로 쏜다 — IdP 역할.

LDAP 은 원천 디렉터리를 띄우면 앱이 알아서 읽어 가지만, SCIM 은 우리가 받는 쪽이라
누군가 밖에서 보내 줘야 한다. 자동화 테스트는 그 역할을 테스트 코드가 하고, 이 스크립트는
사람이 실제 앱을 띄워 두고 만져 볼 때 쓴다.

**순차로 보낸다.** IdP 의 실제 프로비저닝이 그렇고, 동시에 쏘면 재려던 것 대신 동시성
처리를 재게 된다 — 그건 별도 시나리오(S3)의 몫이다.

  ./gradlew :connector-scim:generateScimSeed
  docker compose up -d openfga dynamodb-local
  ./gradlew :app-scim:bootRun
  python3 docker/scim/replay.py
"""
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8082"
SEED = pathlib.Path(__file__).with_name("initial-sync.ndjson")


def send(request):
    body = json.dumps(request["body"]).encode("utf-8")
    req = urllib.request.Request(
        BASE + request["path"],
        data=body,
        method=request["method"],
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req) as response:
            return response.status
    except urllib.error.HTTPError as e:
        return e.code


def main():
    if not SEED.exists():
        sys.exit(f"시드가 없다: {SEED}\n  ./gradlew :connector-scim:generateScimSeed")

    requests = [json.loads(line) for line in SEED.read_text(encoding="utf-8").splitlines()]
    print(f"{len(requests)}건을 {BASE} 로 보낸다")

    실패 = []
    시작 = time.time()
    for i, request in enumerate(requests, 1):
        status = send(request)
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
