"""외부 API 목 서버 (성능 측정 전용).

고용24 훈련과정 목록, 온통청년 정책 목록, 행정표준코드(법정동) API를 흉내 낸다.
실제 응답 형식(필드명·페이지 규칙)은 data-service 클라이언트 코드에 맞췄고, 값은 합성 데이터다.

- 같은 (day, 출처, 순번)은 항상 같은 레코드를 만든다. 회차를 반복해도 입력이 같다.
- 경로 앞의 /d0, /d1 이 '날짜'다. d1은 d0 대비 약 10%를 바꾸고 0.5%를 새로 더한다
  (수집 동시 시나리오에서 '다음 날 수집'을 재현).
- 응답 지연은 로그정규분포(기본 p50 139ms, p95 273ms: 과거 고용24 실측 표본)로 넣는다.

표준 라이브러리만 쓴다. 실행: python ext_mock.py  (PORT, MOCK_* 환경변수로 조정)
"""
import hashlib
import json
import math
import os
import random
import time
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("PORT", "8090"))
ANCHOR = date.fromisoformat(os.environ.get("MOCK_ANCHOR_DATE", "2026-11-02"))
E24_TOTAL = int(os.environ.get("MOCK_E24_TOTAL", "140000"))
YP_TOTAL = int(os.environ.get("MOCK_YP_TOTAL", "4700"))
LAT_MEDIAN = float(os.environ.get("MOCK_LATENCY_MEDIAN_MS", "139"))
LAT_P95 = float(os.environ.get("MOCK_LATENCY_P95_MS", "273"))
LAT_SIGMA = math.log(LAT_P95 / LAT_MEDIAN) / 1.645 if LAT_P95 > LAT_MEDIAN > 0 else 0.0
CHANGE_RATIO = float(os.environ.get("MOCK_CHANGE_RATIO", "0.10"))
NEW_RATIO = float(os.environ.get("MOCK_NEW_RATIO", "0.005"))

# ---------------------------------------------------------------- 지역 (합성, 시·도 코드는 실제 값)
SIDO = [
    ("11", "서울특별시", 25), ("26", "부산광역시", 16), ("27", "대구광역시", 9), ("28", "인천광역시", 10),
    ("29", "광주광역시", 5), ("30", "대전광역시", 5), ("31", "울산광역시", 5), ("41", "경기도", 31),
    ("43", "충청북도", 11), ("44", "충청남도", 15), ("46", "전라남도", 22), ("47", "경상북도", 22),
    ("48", "경상남도", 18), ("50", "제주특별자치도", 2), ("51", "강원특별자치도", 18), ("52", "전북특별자치도", 14),
]
SEOUL_GU = ["종로구", "중구", "용산구", "성동구", "광진구", "동대문구", "중랑구", "성북구", "강북구", "도봉구",
            "노원구", "은평구", "서대문구", "마포구", "양천구", "강서구", "구로구", "금천구", "영등포구", "동작구",
            "관악구", "서초구", "강남구", "송파구", "강동구"]


def _build_regions():
    rows = []
    sigungu = []  # (5자리, 시도명, 시군구명)
    for sido_cd, sido_nm, n in SIDO:
        rows.append({"region_cd": f"{sido_cd}00000000", "sido_cd": sido_cd, "sgg_cd": "000", "umd_cd": "000",
                     "ri_cd": "00", "locatadd_nm": sido_nm, "locathigh_cd": "0000000000", "locallow_nm": sido_nm})
        for k in range(n):
            sgg = f"{110 + 25 * k:03d}"
            if sido_cd == "11":
                name = SEOUL_GU[k]
            else:
                suffix = "구" if sido_cd in ("26", "27", "28", "29", "30", "31") else ("시" if k % 3 == 0 else "군")
                name = f"{sido_nm[:2]}{k + 1:02d}{suffix}"
            code = f"{sido_cd}{sgg}00000"
            rows.append({"region_cd": code, "sido_cd": sido_cd, "sgg_cd": sgg, "umd_cd": "000", "ri_cd": "00",
                         "locatadd_nm": f"{sido_nm} {name}", "locathigh_cd": f"{sido_cd}00000000",
                         "locallow_nm": name})
            sigungu.append((f"{sido_cd}{sgg}", sido_nm, name))
    rows.append({"region_cd": "3611000000", "sido_cd": "36", "sgg_cd": "110", "umd_cd": "000", "ri_cd": "00",
                 "locatadd_nm": "세종특별자치시", "locathigh_cd": "0000000000", "locallow_nm": "세종특별자치시"})
    sigungu.append(("36110", "세종특별자치시", "세종특별자치시"))
    return rows, sigungu


REGION_ROWS, SIGUNGU = _build_regions()
ALL_ZIP = ",".join(code for code, _, _ in SIGUNGU)

# ---------------------------------------------------------------- 공통


def rng(*parts):
    seed = hashlib.sha256("|".join(str(p) for p in parts).encode()).hexdigest()[:16]
    return random.Random(int(seed, 16))


def bucket(*parts):
    """0~1 사이 고정 값 (변경·신규 대상 선택용)"""
    h = hashlib.sha256(("b|" + "|".join(str(p) for p in parts)).encode()).hexdigest()[:8]
    return int(h, 16) / 0xFFFFFFFF


def ymd(d):
    return d.strftime("%Y%m%d")


def sleep_latency():
    if LAT_MEDIAN <= 0:
        return
    ms = LAT_MEDIAN * math.exp(random.gauss(0, LAT_SIGMA)) if LAT_SIGMA else LAT_MEDIAN
    time.sleep(min(ms, 5000) / 1000)


# ---------------------------------------------------------------- 고용24
NCS_MID = {}
_NCS_MID_LIST = ("0101 0201 0202 0203 0204 0301 0302 0402 0403 0501 0502 0601 0602 0701 0702 0703 0801 0802 0803 "
                 "0901 0902 0903 0904 1001 1002 1003 1101 1102 1201 1202 1203 1204 1301 1401 1402 1403 1404 1405 "
                 "1406 1407 1408 1501 1502 1503 1504 1505 1506 1507 1508 1509 1510 1511 1601 1602 1701 1702 1703 "
                 "1704 1705 1801 1802 1803 1901 1902 1903 2001 2002 2003 2101 2102 2201 2202 2301 2302 2303 2304 "
                 "2305 2306 2401 2402 2403 2404").split()
for mid in _NCS_MID_LIST:
    NCS_MID.setdefault(mid[:2], []).append(mid)
# NCS 대분류별 건수 비중 (정보통신·사업관리·경영 등에 몰리도록)
NCS_WEIGHT = {"01": 6, "02": 14, "03": 3, "04": 3, "05": 1, "06": 3, "07": 4, "08": 9, "09": 2, "10": 3,
              "11": 1, "12": 3, "13": 5, "14": 3, "15": 4, "16": 1, "17": 1, "18": 2, "19": 5, "20": 18,
              "21": 2, "22": 2, "23": 4, "24": 2}
_W = sum(NCS_WEIGHT.values())
NCS_COUNT = {k: max(1, round(E24_TOTAL * v / _W)) for k, v in NCS_WEIGHT.items()}

TARGETS = [("C0061", "국민내일배움카드(일반)", 40), ("C0061S", "국민내일배움카드(구직자)", 10),
           ("C0061I", "국민내일배움카드(재직자)", 20), ("C0054", "국가기간전략산업직종", 8),
           ("C0104", "K-디지털 트레이닝", 4), ("C0105", "K-디지털 기초역량훈련", 3),
           ("C0055", "실업자 원격훈련", 15)]
_TARGET_POOL = [t for t in TARGETS for _ in range(t[2])]
TOPIC_WORDS = {
    "20": ["자바 백엔드", "파이썬 데이터분석", "클라우드 엔지니어", "웹 프론트엔드", "AI 서비스 개발", "정보보안", "모바일 앱 개발"],
    "02": ["전산회계", "세무실무", "ERP 정보관리", "사무행정", "인사노무 실무"],
    "08": ["UX/UI 디자인", "영상편집", "웹디자인", "3D 모델링", "콘텐츠 마케팅"],
    "13": ["한식조리", "제과제빵", "바리스타", "양식조리"],
    "19": ["전기기능사", "PLC 제어", "반도체 장비", "전자회로 설계"],
}
TITLE_SUFFIX = ["양성과정", "실무과정", "취업과정", "전문가과정", "기초과정", "심화과정"]


def e24_record(day, ncs1, idx):
    r = rng("e24", ncs1, idx)
    mid = r.choice(NCS_MID.get(ncs1, [ncs1 + "01"]))
    ncs_cd = f"{mid}{r.randint(1, 9):02d}{r.randint(1, 9):02d}"
    topic = r.choice(TOPIC_WORDS.get(ncs1, ["직무역량", "자격증 취득", "현장실무", "기술실무"]))
    title = f"{topic} {r.choice(TITLE_SUFFIX)}"
    if r.random() < 0.3:
        title = f"[{r.choice(['국비', 'KDT', '야간', '주말'])}] {title}"
    zip5, sido_nm, sgg_nm = r.choice(SIGUNGU)
    target_cd, target_nm, _ = r.choice(_TARGET_POOL)
    start = ANCHOR + timedelta(days=r.randint(0, 180))
    end = start + timedelta(days=r.choice([20, 40, 60, 90, 120, 180]))
    real = r.choice([300000, 800000, 1500000, 2500000, 4800000, 9000000])
    inst = r.randint(1, 4000)
    rec = {
        "trprId": f"AIG2026{ncs1}{idx:07d}",
        "trprDegr": str(r.randint(1, 12)),
        "title": title,
        "subTitle": f"{sgg_nm} 직업전문학교 {inst}",
        "instCd": f"{500000 + inst}",
        "trainstCstId": f"5000{inst:06d}",
        "traStartDate": start.isoformat(),
        "traEndDate": end.isoformat(),
        "realMan": str(real),
        "courseMan": str(int(real * r.choice([0, 0.1, 0.15, 0.2]))),
        "yardMan": str(r.choice([15, 20, 25, 30, 40])),
        "ncsCd": ncs_cd,
        "trainTarget": target_nm,
        "trainTargetCd": target_cd,
        "wkendSe": r.choice(["1", "2", "3", "9"]),
        "trngAreaCd": zip5,
        "address": f"{sido_nm} {sgg_nm} 테스트로 {r.randint(1, 300)}",
        "telNo": f"02-{r.randint(200, 999)}-{r.randint(1000, 9999)}",
        "titleLink": f"https://www.work24.go.kr/mock/{ncs1}/{idx}",
        "regCourseMan": str(r.randint(0, 30)),
        "eiEmplRate3": str(r.randint(0, 100)),
    }
    if day >= 1 and bucket("e24", ncs1, idx) < CHANGE_RATIO:
        # 다음 날: 정원·종료일 변경 (정규화 버전이 바뀌는 변경)
        rec["yardMan"] = str(int(rec["yardMan"]) + 5)
        rec["traEndDate"] = (end + timedelta(days=7)).isoformat()
    return rec


def e24_page(day, ncs1, page_num, page_size):
    base = NCS_COUNT.get(ncs1, 0)
    total = base + (int(base * NEW_RATIO) if day >= 1 else 0)
    start = (page_num - 1) * page_size
    items = [e24_record(day, ncs1, i) for i in range(start, min(start + page_size, total))]
    return {"scn_cnt": total, "pageNum": page_num, "pageSize": page_size, "srchList": items}


# ---------------------------------------------------------------- 온통청년
YP_CATEGORIES = [("일자리", ["취업", "창업", "재직자"]), ("주거", ["주택 및 거주지", "기숙사", "전월세 및 주거급여 지원"]),
                 ("교육", ["미래역량강화", "교육비지원", "온라인교육"]), ("복지문화", ["취약계층 및 금융지원", "건강", "예술인지원", "문화활동"]),
                 ("참여권리", ["청년참여", "정책인프라구축", "청년국제교류", "권익보호"])]
YP_NAMES = ["청년 월세 지원", "청년 취업 준비 지원금", "청년 창업 공간 지원", "청년 마음건강 바우처", "청년 문화패스",
            "청년 자격증 응시료 지원", "청년 해외연수", "청년 전월세 보증금 이자 지원", "청년 면접 정장 대여",
            "청년 디지털 역량 교육", "청년 내일저축계좌", "청년 교통비 지원", "청년 인턴십", "청년 농업인 정착 지원"]
CODE_SETS = {
    "mrgSttsCd": ["0055003"] * 8 + ["0055002", "0055001"],
    "jobCd": ["0013010"] * 6 + ["0013003", "0013001", "0013006", "0013003,0013007"],
    "schoolCd": ["0049010"] * 7 + ["0049005", "0049007", "0049004,0049005,0049006"],
    "plcyMajorCd": ["0011009"] * 9 + ["0011005"],
    "sbizCd": ["0014010"] * 8 + ["0014003", "0014005"],
}


def yp_record(day, idx):
    r = rng("yp", idx)
    lcl, mcls = r.choice(YP_CATEGORIES)
    scope = r.random()
    if scope < 0.3:
        zip_cd, region_word = ALL_ZIP, ""
    elif scope < 0.7:
        sido = r.choice(SIDO)
        zip_cd = ",".join(c for c, s, _ in SIGUNGU if c.startswith(sido[0]))
        region_word = sido[1][:2] + " "
    else:
        c, s, g = r.choice(SIGUNGU)
        zip_cd, region_word = c, g + " "
    name = f"{region_word}{r.choice(YP_NAMES)}"
    period = r.random()
    if period < 0.55:
        start = ANCHOR + timedelta(days=r.randint(-60, 60))
        end = start + timedelta(days=r.choice([14, 30, 60, 90]))
        aply_cd, aply_ymd = "0057001", f"{ymd(start)} ~ {ymd(end)}"
    elif period < 0.85:
        aply_cd, aply_ymd, end = "0057002", "", None
    else:
        aply_cd, aply_ymd, end = "0057003", "", None
    min_age = r.choice([0, 15, 18, 19, 20])
    max_age = r.choice([0, 29, 34, 35, 39])
    earn = r.random()
    rec = {
        "plcyNo": f"2026{idx:08d}",
        "plcyNm": name,
        "plcyKywdNm": ",".join(r.sample(["바우처", "보조금", "대출", "교육지원", "인턴", "장학금", "공공임대주택", "금리혜택"], 2)),
        "plcyExplnCn": f"{name} 사업입니다. 대상 청년에게 {mcls} 관련 지원을 제공합니다.",
        "lclsfNm": lcl,
        "mclsfNm": mcls,
        "plcySprtCn": f"1인당 최대 {r.choice([10, 30, 50, 100, 300])}만원 지원",
        "sprvsnInstCdNm": f"{region_word.strip() or '고용노동부'} 청년정책과",
        "operInstCdNm": f"{region_word.strip() or '한국고용정보원'} 청년센터",
        "aplyPrdSeCd": aply_cd,
        "aplyYmd": aply_ymd,
        "plcyAplyMthdCn": "온라인 신청",
        "srngMthdCn": "서류 심사",
        "sbmsnDcmntCn": "신청서, 주민등록등본",
        "aplyUrlAddr": f"https://www.youthcenter.go.kr/mock/{idx}",
        "refUrlAddr1": "",
        "refUrlAddr2": "",
        "sprtSclCnt": str(r.choice([0, 50, 100, 500, 1000])),
        "sprtTrgtMinAge": str(min_age),
        "sprtTrgtMaxAge": str(max_age),
        "sprtTrgtAgeLmtYn": "N" if min_age or max_age else "Y",
        "mrgSttsCd": r.choice(CODE_SETS["mrgSttsCd"]),
        "earnCndSeCd": "0043001" if earn < 0.7 else ("0043002" if earn < 0.9 else "0043003"),
        "earnMinAmt": "0",
        "earnMaxAmt": "0" if earn < 0.7 else str(r.choice([3000, 4000, 5000])),
        "earnEtcCn": "" if earn < 0.9 else "기준 중위소득 150% 이하",
        "addAplyQlfcCndCn": r.choice(["", "", "해당 지역에 거주하는 청년", "만 19~34세 미취업 청년"]),
        "ptcpPrpTrgtCn": r.choice(["", "", "", "동일 사업 기수혜자 제외"]),
        "bizPrdBgngYmd": ymd(ANCHOR - timedelta(days=200)),
        "bizPrdEndYmd": ymd(ANCHOR + timedelta(days=160)),
        "bizPrdEtcCn": "",
        "zipCd": zip_cd,
        "plcyMajorCd": r.choice(CODE_SETS["plcyMajorCd"]),
        "jobCd": r.choice(CODE_SETS["jobCd"]),
        "schoolCd": r.choice(CODE_SETS["schoolCd"]),
        "sbizCd": r.choice(CODE_SETS["sbizCd"]),
        "etcMttrCn": "",
        "frstRegDt": f"{ymd(ANCHOR - timedelta(days=r.randint(1, 400)))}120000",
        "inqCnt": str(r.randint(0, 5000)),
    }
    if day >= 1 and bucket("yp", idx) < CHANGE_RATIO and aply_cd == "0057001":
        rec["aplyYmd"] = f"{aply_ymd[:8]} ~ {ymd(end + timedelta(days=14))}"  # 마감 연장
    elif day >= 1 and bucket("yp", idx) < CHANGE_RATIO:
        rec["sprtSclCnt"] = str(int(rec["sprtSclCnt"]) + 10)
    return rec


def yp_page(day, page_num, page_size):
    total = YP_TOTAL + (int(YP_TOTAL * NEW_RATIO) if day >= 1 else 0)
    start = (page_num - 1) * page_size
    items = [yp_record(day, i) for i in range(start, min(start + page_size, total))]
    return {"resultCode": 200, "resultMessage": "정상적으로 처리되었습니다.",
            "result": {"pagging": {"totCount": total, "pageNum": page_num, "pageSize": page_size},
                       "youthPolicyList": items}}


# ---------------------------------------------------------------- 지역 코드


def region_page(page_no, rows):
    start = (page_no - 1) * rows
    part = REGION_ROWS[start:start + rows]
    return {"StanReginCd": [
        {"head": [{"totalCount": len(REGION_ROWS)}, {"numOfRows": str(rows), "pageNo": str(page_no), "type": "json"},
                  {"RESULT": {"resultCode": "INFO-0", "resultMsg": "NORMAL SERVICE"}}]},
        {"row": part}]}


# ---------------------------------------------------------------- HTTP
STATS = {"employment24": 0, "youth-policy": 0, "region": 0}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 요청마다 로그를 남기지 않는다 (측정 노이즈)
        pass

    def _send(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        parts = [p for p in u.path.split("/") if p]
        if parts == ["health"]:
            return self._send(200, {"ok": True, "stats": STATS, "e24Total": sum(NCS_COUNT.values()), "ypTotal": YP_TOTAL})
        day = 0
        if parts and parts[0].startswith("d") and parts[0][1:].isdigit():
            day = int(parts[0][1:])
            parts = parts[1:]
        try:
            if parts == ["employment24"]:
                STATS["employment24"] += 1
                sleep_latency()
                return self._send(200, e24_page(day, q.get("srchNcs1", "01").zfill(2),
                                                int(q.get("pageNum", "1")), int(q.get("pageSize", "100"))))
            if parts == ["youth-policy"]:
                STATS["youth-policy"] += 1
                sleep_latency()
                return self._send(200, yp_page(day, int(q.get("pageNum", "1")), int(q.get("pageSize", "100"))))
            if parts == ["region"]:
                STATS["region"] += 1
                return self._send(200, region_page(int(q.get("pageNo", "1")), int(q.get("numOfRows", "1000"))))
        except ValueError:
            return self._send(400, {"error": "bad parameter"})
        return self._send(404, {"error": "not found", "path": u.path})


if __name__ == "__main__":
    print(f"ext-mock :{PORT} e24={sum(NCS_COUNT.values())} yp={YP_TOTAL} regions={len(REGION_ROWS)} "
          f"latency p50={LAT_MEDIAN}ms p95={LAT_P95}ms anchor={ANCHOR}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
