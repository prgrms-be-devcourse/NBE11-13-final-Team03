-- Y:ON local-only sample data (2026-10-26)
-- Apply only to local Docker PostgreSQL yon/application.
-- Intentionally omits source/eligibility/notification data: this seeds search/detail UI, not end-to-end recommendation.
-- Training classification follows TrainingCourseNormalizationService fallback for unknown NCS codes.
-- Training application window is UNKNOWN as no actual recruitment period is provided by source API.
BEGIN;

INSERT INTO application.regions(region_code, region_name, parent_code) VALUES
 ('11', '서울특별시', NULL),
 ('26', '부산광역시', NULL),
 ('41', '경기도', NULL)
ON CONFLICT (region_code) DO NOTHING;

DO $seed$
DECLARE
  i integer;
  v_opportunity_id bigint;
  v_version_id bigint;
  v_title text;
  v_type text;
  v_category text;
  v_sub_category text;
  v_region text;
  v_period text;
  v_period_text text;
  v_from date;
  v_to date;
  v_today date := (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date;
  v_training_start date;
  v_seed_key text;
  v_match_count integer;
  v_region_role text;
  v_region_snapshot jsonb;
BEGIN
  FOR i IN 1..20 LOOP
    v_type := CASE WHEN i <= 10 THEN 'YOUTH_POLICY' ELSE 'VOCATIONAL_TRAINING' END;
    v_title := '[LOCAL-TEST] ' || lpad(i::text, 2, '0') || ' ' ||
      CASE WHEN i <= 10 THEN
        (ARRAY['청년 취업 준비 지원','청년 면접 역량 강화','청년 주거비 지원','사회초년생 금융교육','지역 청년 창업 지원',
               '청년 자격증 응시료 지원','청년 커리어 상담','청년 문화 활동 지원','청년 일자리 매칭','청년 교통비 지원'])[i]
      ELSE
        (ARRAY['Java 백엔드 개발자 과정','Spring Boot 서버 개발','데이터베이스 설계 실무','클라우드 인프라 기초','웹 프론트엔드 개발',
               '데이터 분석 입문','정보보안 실무','AI 서비스 개발','DevOps 자동화','API 테스트 및 품질'])[i-10]
      END;

    v_category := CASE WHEN i <= 10 THEN
      (ARRAY['일자리','일자리','주거','금융','창업','교육','일자리','문화','일자리','복지'])[i]
      ELSE '교육･직업훈련' END;
    v_sub_category := CASE WHEN i <= 10 THEN
      (ARRAY['취업지원','취업지원','월세지원','금융교육','창업지원','역량강화','진로상담','문화지원','취업지원','생활지원'])[i]
      ELSE '직업훈련'
      END;
    v_region := (ARRAY['11','41','26'])[((i - 1) % 3) + 1];
    v_region_role := CASE WHEN v_type = 'VOCATIONAL_TRAINING' THEN 'IMPLEMENTATION' ELSE 'RESIDENCE' END;
    v_region_snapshot := jsonb_build_object('status', 'SPECIFIC', 'codes', jsonb_build_array(v_region),
                                            'unmappedCodes', '[]'::jsonb, 'validationStatus', 'VALID');
    v_seed_key := 'LOCAL-TEST-' || lpad(i::text, 2, '0');
    v_period := CASE WHEN v_type = 'VOCATIONAL_TRAINING' THEN 'UNKNOWN' WHEN i % 5 = 0 THEN 'ALWAYS' WHEN i % 5 = 1 THEN 'FIXED' WHEN i % 5 = 2 THEN 'FIXED' WHEN i % 5 = 3 THEN 'CLOSED' ELSE 'UNKNOWN' END;
    v_from := CASE WHEN v_period IN ('FIXED','CLOSED') THEN v_today - 7 ELSE NULL END;
    v_to := CASE WHEN v_period = 'FIXED' AND i % 5 = 1 THEN v_today + 3
                  WHEN v_period = 'FIXED' THEN v_today + 21
                  WHEN v_period = 'CLOSED' THEN v_today - 1 ELSE NULL END;
    v_period_text := CASE WHEN v_type = 'VOCATIONAL_TRAINING' THEN '모집기간 정보 없음 (로컬 테스트)'
                          WHEN v_period = 'ALWAYS' THEN '상시 모집 (로컬 테스트)'
                          WHEN v_period = 'UNKNOWN' THEN '모집 기간 확인 필요 (로컬 테스트)'
                          ELSE '로컬 테스트 기간' END;
    v_training_start := v_today + CASE WHEN i % 3 = 0 THEN 5 WHEN i % 3 = 1 THEN 14 ELSE 28 END;

    SELECT count(*), max(o.id), max(v.id)
      INTO v_match_count, v_opportunity_id, v_version_id
      FROM application.opportunities o
      JOIN application.opportunity_versions v ON v.id = o.current_version_id AND v.opportunity_id = o.id
      LEFT JOIN application.youth_policy_details y ON y.version_id = v.id
      LEFT JOIN application.training_course_details t ON t.version_id = v.id
     WHERE o.title = v_title AND o.opportunity_type = v_type
       AND v.normalization_version = 'local-seed-v1'
       AND v.normalized_snapshot->>'testData' = 'true'
       AND ((v_type = 'YOUTH_POLICY' AND y.policy_no = v_seed_key)
         OR (v_type = 'VOCATIONAL_TRAINING' AND t.course_id = v_seed_key));
    IF v_match_count > 1 THEN
      RAISE EXCEPTION '중복된 로컬 시드 공고: %', v_seed_key;
    END IF;

    IF v_match_count = 0 THEN
      IF EXISTS (SELECT 1 FROM application.opportunities WHERE title = v_title) THEN
        RAISE EXCEPTION '로컬 시드 제목과 충돌하는 공고: %', v_title;
      END IF;

      INSERT INTO application.opportunities
        (title, opportunity_type, category, sub_category, detail_category, detail_url, created_at, updated_at)
      VALUES (v_title, v_type, v_category, v_sub_category, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
      RETURNING id INTO v_opportunity_id;

      INSERT INTO application.opportunity_versions
        (opportunity_id, version_no, normalization_version, normalized_snapshot, personalization_status, created_at)
      VALUES (v_opportunity_id, 1, 'local-seed-v1',
              jsonb_build_object('testData', true, 'title', v_title, 'type', v_type, 'region', v_region_snapshot),
              'PERSONALIZABLE', CURRENT_TIMESTAMP)
      RETURNING id INTO v_version_id;

      UPDATE application.opportunities SET current_version_id = v_version_id WHERE id = v_opportunity_id;

      INSERT INTO application.application_windows
        (version_id, period_type, start_date, end_date, period_text, validation_status)
      VALUES (v_version_id, v_period, v_from, v_to, v_period_text,
              CASE WHEN v_period = 'UNKNOWN' THEN 'UNKNOWN' ELSE 'VALID' END);

      INSERT INTO application.opportunity_regions(version_id, region_code, region_role)
      VALUES (v_version_id, v_region, v_region_role);

      IF v_type = 'YOUTH_POLICY' THEN
        INSERT INTO application.youth_policy_details
          (version_id, policy_no, description, support_content, application_method,
           operating_institution_name, supervising_institution_name, first_registered_date)
        VALUES (v_version_id, v_seed_key,
                '로컬 프론트엔드 및 검색 API 확인을 위한 가상 청년정책이다. 실제 신청할 수 없다.',
                '테스트용 지원 내용', '온라인 신청 (가상)', 'Y:ON 테스트 기관', 'Y:ON 테스트 기관', v_today);
      ELSE
        INSERT INTO application.training_course_details
          (version_id, course_id, course_round, institution_name,
           training_start_date, training_end_date, capacity, course_fee, ncs_code)
        VALUES (v_version_id, v_seed_key, 1, 'Y:ON 테스트 교육기관',
                v_training_start, v_today + 120, 25, 0, NULL);
      END IF;
    ELSE
      IF (SELECT count(*) FROM application.application_windows WHERE version_id = v_version_id) <> 1
         OR (SELECT count(*) FROM application.opportunity_regions WHERE version_id = v_version_id
             AND region_code = v_region AND region_role = v_region_role) <> 1 THEN
        RAISE EXCEPTION '로컬 시드의 신청기간 또는 지역 연결이 예상과 다름: %', v_seed_key;
      END IF;

      UPDATE application.opportunities
         SET category = v_category, sub_category = v_sub_category, detail_category = NULL,
             updated_at = CURRENT_TIMESTAMP
       WHERE id = v_opportunity_id
         AND (category, sub_category, detail_category)
             IS DISTINCT FROM (v_category, v_sub_category, NULL::text);

      UPDATE application.application_windows
         SET period_type = v_period, start_date = v_from, end_date = v_to,
             period_text = v_period_text,
             validation_status = CASE WHEN v_period = 'UNKNOWN' THEN 'UNKNOWN' ELSE 'VALID' END
       WHERE version_id = v_version_id;

      IF v_type = 'VOCATIONAL_TRAINING' THEN
        UPDATE application.training_course_details
           SET training_start_date = v_training_start, training_end_date = v_today + 120
         WHERE version_id = v_version_id;
      END IF;

      UPDATE application.opportunity_versions
         SET normalized_snapshot = jsonb_set(normalized_snapshot, '{region}', v_region_snapshot, true)
       WHERE id = v_version_id AND normalized_snapshot->'region' IS DISTINCT FROM v_region_snapshot;
    END IF;

    IF v_type = 'YOUTH_POLICY' THEN
      INSERT INTO application.opportunity_conditions
        (version_id, condition_type, requirement_type, condition_field, expression, evidence, validation_status, created_at)
      SELECT v_version_id, 'REGION', 'REQUIRED', 'residenceRegion',
             jsonb_build_object('status', 'SPECIFIC', 'role', 'RESIDENCE'),
             jsonb_build_object('sourceType', 'LOCAL_SEED', 'evidenceText', '로컬 테스트용 거주지역'),
             'VALID', CURRENT_TIMESTAMP
       WHERE NOT EXISTS (
         SELECT 1 FROM application.opportunity_conditions
          WHERE version_id = v_version_id AND condition_type = 'REGION'
            AND expression->>'role' = 'RESIDENCE'
       );
    END IF;
  END LOOP;
END
$seed$;
COMMIT;

SELECT opportunity_type, COUNT(*) AS test_count
FROM application.opportunities
WHERE title LIKE '[LOCAL-TEST] %'
GROUP BY opportunity_type
ORDER BY opportunity_type;
