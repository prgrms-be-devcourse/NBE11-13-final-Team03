"""고용24 공통코드 API(319L01)로 NCS 대분류·중분류 코드표(ncs-codes.tsv)를 만든다 (홈서버 전용, 표준 라이브러리만 사용).

- 대분류: srchType=05
- 중분류: srchType=06, srchOption1=대분류 코드 (대분류마다 1번씩 호출)
- returnType은 XML만 지원 (명세)
키: 환경변수 EMPLOYMENT24_CODE_API_KEY (공통코드 API용 키가 따로 나온 경우), 없으면 EMPLOYMENT24_API_KEY.
    실행 전 set -a; . ~/yon-e2e.env; set +a. 키는 화면·파일에 남기지 않는다.
실행: python3 ncs_codes_fetch.py  → ./ncs-codes.tsv 생성, 화면에 요약
결과 파일을 backend/src/main/resources/ncs-codes.tsv로 복사해 커밋한다 (앱은 API를 부르지 않고 이 파일만 읽음)

불완전한 표가 만들어지지 않도록, 아래 중 하나라도 있으면 파일을 만들지 않고 중단한다 (기존 파일도 그대로 둔다).
- 호출 실패, XML이 아닌 응답, <error> 응답
- 목록(scn_list)이 없는 응답. NCS는 모든 대분류 아래에 중분류가 있으므로 중분류 0개도 비정상으로 본다
- 코드·이름이 빠진 항목, 형식이 다른 코드, 대분류와 앞자리가 다른 중분류 코드, 중복 코드
"""
import datetime
import os
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

URL = "https://www.work24.go.kr/cm/openApi/call/hr/callOpenApiSvcInfo319L01.do"
OUTPUT = "ncs-codes.tsv"


def call(srch_type, option1=None):
    label = f"srchType={srch_type} option1={option1}"
    params = {"authKey": os.environ.get("EMPLOYMENT24_CODE_API_KEY") or os.environ["EMPLOYMENT24_API_KEY"],
              "returnType": "XML", "outType": "1", "srchType": srch_type}
    if option1:
        params["srchOption1"] = option1
    req = urllib.request.Request(URL + "?" + urllib.parse.urlencode(params),
                                 headers={"User-Agent": "Mozilla/5.0 (yon ncs code fetch)"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            body = resp.read()
    except Exception as e:  # URL에 키가 있으므로 예외 메시지 대신 종류만 출력
        sys.exit(f"호출 실패: {label} ({type(e).__name__})")
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        sys.exit(f"XML 아님: {label}\n{body[:500].decode('utf-8', 'replace')}")
    error = next((e.text for e in root.iter("error") if e.text and e.text.strip()), None)
    if error:
        sys.exit(f"API 오류: {label} → {error.strip()}\n"
                 "(서비스 미신청이면 고용24 OPEN-API에서 '공통코드 - 훈련'을 신청하고 인증키를 확인하세요)")
    items = list(root.iter("scn_list")) or list(root.iter("SCN_LIST"))
    if not items:
        sys.exit(f"목록(scn_list)이 없는 응답: {label}\n{body[:300].decode('utf-8', 'replace')}")
    rows = []
    for item in items:
        code = (item.findtext("rsltCode") or item.findtext("RSLT_CODE") or "").strip()
        name = (item.findtext("rsltName") or item.findtext("RSLT_NAME") or "").strip()
        use = (item.findtext("useYn") or item.findtext("USE_YN") or "").strip()
        if not code or not name:
            sys.exit(f"코드나 이름이 빠진 항목: {label} code={code!r} name={name!r}")
        rows.append((code, name, use))
    return rows


def main():
    if not (os.environ.get("EMPLOYMENT24_CODE_API_KEY") or os.environ.get("EMPLOYMENT24_API_KEY")):
        sys.exit("EMPLOYMENT24_CODE_API_KEY 또는 EMPLOYMENT24_API_KEY가 없습니다. set -a; . ~/yon-e2e.env; set +a 후 실행하세요")
    majors = call("05")
    print(f"대분류 {len(majors)}개: " + ", ".join(f"{c} {n}" for c, n, _ in majors))

    lines = []
    unused = []
    for code, name, use in majors:
        if use and use != "Y":
            unused.append(code)
        lines.append((code.zfill(2), name))
    middle_count = 0
    for major, _, _ in majors:
        time.sleep(0.5)
        prefix = major.zfill(2)
        for code, name, use in call("06", major):
            full = code if len(code) == 4 else prefix + code.zfill(2)
            if not full.startswith(prefix):
                sys.exit(f"대분류 {prefix} 아래인데 앞자리가 다른 중분류 코드: {full} {name}")
            if use and use != "Y":
                unused.append(full)
            lines.append((full, name))
            middle_count += 1
    print(f"중분류 {middle_count}개")
    if unused:
        print(f"사용여부 N (그래도 파일에 넣음, 과거 코드 대비): {', '.join(unused)}")

    codes = [c for c, _ in lines]
    bad = [c for c in codes if not (c.isdigit() and len(c) in (2, 4))]
    if bad:
        sys.exit(f"예상 밖 코드 형식: {bad[:10]}")
    duplicates = sorted({c for c in codes if codes.count(c) > 1})
    if duplicates:
        sys.exit(f"중복 코드: {duplicates}")

    # 임시 파일에 다 쓴 뒤 바꿔치기: 중간에 실패해도 기존 파일이 반쯤 덮이지 않는다
    today = datetime.date.today().isoformat()
    tmp = OUTPUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as out:
        out.write("# NCS 분류 코드 → 이름 (NcsClassification)\n")
        out.write("# 형식: 코드<TAB>이름. 2자리 = 대분류(1차), 4자리 = 중분류(2차)\n")
        out.write(f"# 출처: 고용24 공통코드 API callOpenApiSvcInfo319L01 (srchType 05 대분류, 06 중분류), {today} 생성\n")
        out.write("# 다시 만들기: backend/scripts/ncs_codes_fetch.py (홈서버에서 실행 후 이 파일로 복사)\n")
        for code, name in sorted(lines):
            out.write(f"{code}\t{name}\n")
    os.replace(tmp, OUTPUT)
    print(f"{OUTPUT} 작성: 대분류 {len(majors)}개, 중분류 {middle_count}개")


if __name__ == "__main__":
    main()
