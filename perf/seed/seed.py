"""측정용 시드·토큰 도구 (표준 라이브러리만 사용, tools 컨테이너에서 실행).

  python seed/seed.py wait                      backend 준비 대기 (GET /api/regions 200)
  python seed/seed.py mint                      data/user_ids.csv, data/admin_id.txt → data/users.csv, data/admin.csv
  python seed/seed.py collect [SOURCE...]       관리자 API로 수집 접수 후 완료까지 대기
  python seed/seed.py profiles                  사용자별 판정 프로필·관심사·저장 공고를 API로 넣는다

토큰은 backend TokenProvider와 같은 형식(HS512, iss=yon, sub, role, token_type=ACCESS, session_id)이다.
서명 키는 측정 전용 PERF_JWT_SECRET_KEY(base64)이며 운영 키와 다르다.
"""
import base64
import csv
import hashlib
import hmac
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date, timedelta

BASE = os.environ.get("BASE_URL", "http://backend:8080")
DATA = os.environ.get("PERF_DATA_DIR", "/perf/data")
SECRET_B64 = os.environ.get("PERF_JWT_SECRET_KEY", "")
TOKEN_DAYS = int(os.environ.get("PERF_TOKEN_DAYS", "60"))
SEED = int(os.environ.get("PERF_SEED", "20261101"))


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def mint(user_id, role):
    if not SECRET_B64:
        sys.exit("PERF_JWT_SECRET_KEY가 비어 있습니다 (perf/run.sh init)")
    key = base64.b64decode(SECRET_B64)
    now = int(time.time())
    header = {"typ": "JWT", "alg": "HS512"}
    payload = {"jti": str(uuid.uuid4()), "iss": "yon", "iat": now, "exp": now + TOKEN_DAYS * 86400,
               "sub": str(user_id), "role": role, "token_type": "ACCESS",
               "session_id": f"perf-{user_id}"}
    signing = f"{b64url(json.dumps(header, separators=(',', ':')).encode())}." \
              f"{b64url(json.dumps(payload, separators=(',', ':')).encode())}"
    sig = hmac.new(key, signing.encode(), hashlib.sha512).digest()
    return f"{signing}.{b64url(sig)}"


def call(method, path, token=None, body=None, timeout=30):
    """CSRF는 double-submit: 같은 임의 값을 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더로 보낸다."""
    xsrf = uuid.uuid4().hex
    headers = {"Accept": "application/json", "X-XSRF-TOKEN": xsrf}
    cookies = [f"XSRF-TOKEN={xsrf}"]
    if token:
        cookies.append(f"access_token={token}")
    headers["Cookie"] = "; ".join(cookies)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            raw = res.read()
            return res.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw.decode(errors="replace")[:300]


def read_csv(name):
    with open(os.path.join(DATA, name), newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def cmd_wait(timeout=600):
    end = time.time() + timeout
    while time.time() < end:
        try:
            status, _ = call("GET", "/api/regions", timeout=5)
            if status == 200:
                print("backend ready")
                return
        except Exception:  # 기동 중 연결 거부
            pass
        time.sleep(3)
    sys.exit("backend가 준비되지 않았습니다")


def cmd_mint():
    with open(os.path.join(DATA, "user_ids.csv"), encoding="utf-8") as f:
        ids = [line.strip() for line in f if line.strip().isdigit()]
    with open(os.path.join(DATA, "users.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["userId", "token"])
        for uid in ids:
            w.writerow([uid, mint(uid, "USER")])
    admin_id = open(os.path.join(DATA, "admin_id.txt"), encoding="utf-8").read().strip()
    with open(os.path.join(DATA, "admin.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["userId", "token"])
        w.writerow([admin_id, mint(admin_id, "ADMIN")])
    print(f"minted {len(ids)} user tokens + admin {admin_id}")


def admin_token():
    return read_csv("admin.csv")[0]["token"]


def cmd_collect(sources):
    token = admin_token()
    sources = sources or ["YOUTH_POLICY", "EMPLOYMENT24"]
    jobs = {}
    for source in sources:
        for attempt in range(60):
            status, body = call("POST", f"/api/admin/collection/syncs/{source}?pageSize=100&maxPages=1000", token)
            if status == 202:
                jobs[source] = body["jobId"]
                print(f"accepted {source} job={body['jobId']}")
                break
            print(f"start {source} -> {status} {body}; retry")
            time.sleep(5)
        else:
            sys.exit(f"{source} 수집 접수 실패")
    started = time.time()
    done = {}
    while len(done) < len(jobs):
        time.sleep(5)
        for source, job in jobs.items():
            if source in done:
                continue
            status, body = call("GET", f"/api/admin/collection/syncs/{job}", token)
            if status != 200:
                print(f"poll {source} -> {status} {body}")
                continue
            if body.get("status") not in ("PENDING", "RUNNING"):
                done[source] = body
                print(f"{source} {body.get('status')} fetched={body.get('fetchedCount')} new={body.get('newCount')} "
                      f"updated={body.get('updatedCount')} failedPages={body.get('failedPageCount')} "
                      f"elapsed={time.time() - started:.0f}s")
    bad = {s: b.get("status") for s, b in done.items() if b.get("status") != "SUCCESS"}
    if bad:
        sys.exit(f"수집이 SUCCESS가 아닙니다: {bad}")


# ---------------------------------------------------------------- 프로필·관심사·저장
EMPLOYMENT = ["UNEMPLOYED"] * 45 + ["EMPLOYED"] * 30 + ["PART_TIME"] * 8 + ["FREELANCER"] * 5 + \
             ["STARTUP_PREPARING"] * 5 + ["SELF_EMPLOYED"] * 4 + ["DAY_LABORER"] * 2 + ["FARMER"]
EDUCATION = ["UNIVERSITY"] * 50 + ["HIGH_SCHOOL"] * 25 + ["JUNIOR_COLLEGE"] * 15 + ["GRADUATE_SCHOOL"] * 10
MAJOR = ["HUMANITIES", "SOCIAL_SCIENCE", "BUSINESS_ECONOMICS", "NATURAL_SCIENCE", "ENGINEERING",
         "ARTS_AND_PHYSICAL", "AGRICULTURE", "OTHER"]
CARD = ["ISSUED"] * 35 + ["NOT_ISSUED"] * 40 + ["APPLYING"] * 5 + ["UNKNOWN"] * 20


def cmd_profiles():
    users = read_csv("users.csv")
    status, regions = call("GET", "/api/regions")
    leaves = [r["code"] for r in regions["regions"] if r.get("leaf")]
    status, options = call("GET", "/api/interests/options", users[0]["token"])
    cats = options.get("categories", []) if isinstance(options, dict) else []
    status, page = call("GET", "/api/opportunities?size=100&sort=LATEST")
    opp_ids = [c["opportunityId"] for c in page.get("content", [])] if isinstance(page, dict) else []
    if not leaves or not opp_ids:
        sys.exit(f"지역({len(leaves)}) 또는 공고({len(opp_ids)})가 없습니다. 수집·정규화를 먼저 끝내세요")
    today = date.today()
    fails = {"profile": 0, "interests": 0, "save": 0}
    shown = 0
    for i, u in enumerate(users):
        r = random.Random(SEED + int(u["userId"]))
        age = r.choice(list(range(19, 35)) * 4 + list(range(35, 40)))
        body = {
            "birthDate": (today - timedelta(days=age * 365 + r.randint(0, 364))).isoformat(),
            "residenceRegionCode": r.choice(leaves),
            "employmentStatus": r.choice(EMPLOYMENT),
            "educationLevel": r.choice(EDUCATION),
            "maritalStatus": "SINGLE" if r.random() < 0.85 else "MARRIED",
            "majorField": r.choice(MAJOR),
            "trainingCardStatus": r.choice(CARD),
        }
        s, b = call("PUT", "/api/users/me/eligibility-profile", u["token"], body)
        if s >= 300:
            fails["profile"] += 1
            if shown < 5:
                print(f"profile {u['userId']} -> {s} {b}")
                shown += 1
        if cats:
            picks = []
            for c in r.sample(cats, min(len(cats), r.randint(1, 3))):
                picks.append({"type": "CATEGORY", "code": c["category"]})
                subs = c.get("subCategories") or []
                if subs and r.random() < 0.5:
                    picks.append({"type": "SUB_CATEGORY", "code": r.choice(subs)["subCategory"]})
            s, b = call("PUT", "/api/users/me/interests", u["token"], {"interests": picks})
            if s >= 300:
                fails["interests"] += 1
        for oid in r.sample(opp_ids, r.randint(0, 5)):
            s, b = call("POST", f"/api/opportunities/{oid}/save?source=SEARCH", u["token"])
            if s >= 300 and s != 409:
                fails["save"] += 1
        if (i + 1) % 100 == 0:
            print(f"profiles {i + 1}/{len(users)} fails={fails}", flush=True)
    print(f"profiles done fails={fails}")
    if fails["profile"] > len(users) * 0.05:
        sys.exit("프로필 저장 실패가 5%를 넘습니다. 위 오류를 확인하세요")


def cmd_explain_calls():
    """느린 API를 순서대로 몇 번씩 호출한다 (auto_explain 이 실제 실행 계획을 남기도록). 동시 호출 없음."""
    users = read_csv("users.csv")
    kws = [r["keyword"] for r in read_csv("keywords.csv")][:5]
    plan = [("recommendations", "/api/users/me/recommendations", True)] * 4 + \
           [("eligibility_list", "/api/users/me/opportunities/eligibility?size=20", True)] * 3 + \
           [("search_keyword", f"/api/opportunities?keyword={urllib.parse.quote(k)}&size=20", False) for k in kws[:3]] + \
           [("search_filter", "/api/opportunities?type=VOCATIONAL_TRAINING&sort=START_DATE_ASC&size=20", False)]
    for i, (name, path, auth) in enumerate(plan):
        token = users[(i * 37) % len(users)]["token"] if auth else None
        t = time.time()
        status, _ = call("GET", path, token, timeout=120)
        print(f"{name:18} {status} {(time.time() - t) * 1000:8.0f} ms  {path}", flush=True)


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "wait":
        cmd_wait()
    elif command == "mint":
        cmd_mint()
    elif command == "collect":
        cmd_collect(sys.argv[2:])
    elif command == "profiles":
        cmd_profiles()
    elif command == "explain-calls":
        cmd_explain_calls()
    elif command == "token":  # 디버깅: python seed/seed.py token <userId> [USER|ADMIN]
        print(mint(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "USER"))
    else:
        sys.exit(__doc__)
