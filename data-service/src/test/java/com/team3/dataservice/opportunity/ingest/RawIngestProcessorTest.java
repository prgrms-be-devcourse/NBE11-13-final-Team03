package com.team3.dataservice.opportunity.ingest;

import org.junit.jupiter.api.Tag;

import com.team3.dataservice.collection.domain.RecordResultType;
import com.team3.dataservice.collection.domain.entity.ExternalSource;
import com.team3.dataservice.collection.domain.entity.RawExternalRecord;
import com.team3.dataservice.collection.domain.entity.RawNormalizationRequest;
import com.team3.dataservice.collection.domain.entity.SyncHistory;
import com.team3.dataservice.opportunity.domain.repository.OpportunitySourceRepository;
import com.team3.dataservice.opportunity.normalization.*;
import com.team3.dataservice.opportunity.normalization.official.AgeEvidenceExtractor;
import com.team3.dataservice.opportunity.normalization.official.OfficialSupplementer;
import com.team3.dataservice.opportunity.normalization.official.PeriodEvidenceExtractor;
import com.team3.dataservice.opportunity.service.TrainingCourseNormalizationService;
import com.team3.dataservice.opportunity.service.YouthPolicyNormalizationService;
import com.team3.dataservice.region.directory.RegionDirectory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.team3.dataservice.support.ApplicationPostgresTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 수집 원본 → 정규화 연동을 실제 PostgreSQL로 검증한다. 수집 영역 테이블은 수집 엔티티로 만들고(ddl create-drop) 데이터도 수집 엔티티로 넣는다.
 * 원본 한 건마다 별도 트랜잭션으로 커밋하므로 테스트 트랜잭션은 쓰지 않고, 매 테스트 뒤 테이블을 비운다. 클래스 전용 PostgreSQL·application_user로 실행한다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "yon.normalization.ingest.batch-size=2",
        "yon.normalization.ingest.transaction-size=2"
})
@Import({com.team3.dataservice.opportunity.conditionset.ConditionSetIdCache.class,
        com.team3.dataservice.opportunity.service.OpportunitySourceLookup.class,RawIngestProcessor.class, RawRecordReader.class, RawRecordNormalizer.class,
        TrainingCourseNormalizationService.class, YouthPolicyNormalizationService.class,
        AgeNormalizer.class, ApplicationPeriodNormalizer.class, RegionNormalizer.class, RegionDirectory.class,
        CodeConditionNormalizer.class, IncomeNormalizer.class, YouthPolicyCodeTable.class,
        OfficialSupplementer.class, AgeEvidenceExtractor.class, PeriodEvidenceExtractor.class})
@Tag("postgres")
class RawIngestProcessorTest extends ApplicationPostgresTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 10, 20, 9, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime T2 = T1.plusDays(1);

    @Autowired
    private RawIngestProcessor processor;
    @MockitoSpyBean
    private RawRecordNormalizer normalizer;
    @Autowired
    private OpportunitySourceRepository sourceRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private RegionDirectory regionDirectory;
    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private JdbcTemplate jdbc;
    private ExternalSource work24;
    private ExternalSource youth;
    private SyncHistory work24Sync;
    private SyncHistory youthSync;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc = new JdbcTemplate(dataSource);
        regionDirectory.refresh();
        tx.executeWithoutResult(s -> {
            work24 = new ExternalSource("EMPLOYMENT24", "고용24", "https://www.work24.go.kr", true, T1);
            youth = new ExternalSource("YOUTH_POLICY", "온통청년", "https://www.youthcenter.go.kr", true, T1);
            em.persist(work24);
            em.persist(youth);
            work24Sync = new SyncHistory(work24, "MANUAL", T1);
            youthSync = new SyncHistory(youth, "MANUAL", T1);
            em.persist(work24Sync);
            em.persist(youthSync);
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("""
                TRUNCATE raw_normalization_request, raw_external_record, sync_history, external_source, raw_ingest_states,
                         application_windows, opportunity_conditions, opportunity_regions, training_course_details,
                         opportunity_sources, opportunities, opportunity_versions CASCADE
                """);
    }

    @Test
    void 정제_요청을_처리하면_정규화하고_SUCCESS로_바꾼다() {
        RawExternalRecord course = raw(work24Sync, "[\"AIG1\",\"3\"]", course("AIG1", "3", "웹 개발 과정"), T1);
        RawExternalRecord policy = raw(youthSync, "R2026-1", policy("R2026-1", "청년 월세 지원"), T1);
        request(course, null);
        request(policy, null);

        assertThat(processor.processQueue()).isEqualTo(2);

        assertThat(sourceRepository.findBySourceIdAndExternalId(work24.getId(), "AIG1:3")).isPresent();
        assertThat(sourceRepository.findBySourceIdAndExternalId(youth.getId(), "R2026-1")).isPresent();
        assertThat(requestStatuses()).containsOnly(RawRecordReader.SUCCESS);
        assertThat(stateOutcome(course)).isEqualTo(RawIngestProcessor.NORMALIZED);
        assertThat(processor.reconcile()).isZero();
    }

    @Test
    void 같은_원본에_요청이_여러_건이면_최신_원본만_정규화한다() {
        RawExternalRecord v1 = raw(work24Sync, "[\"AIG1\",\"3\"]", course("AIG1", "3", "웹 개발 과정"), T1);
        RawExternalRecord v2 = raw(work24Sync, "[\"AIG1\",\"3\"]", course("AIG1", "3", "웹 개발 과정 (개정)"), T2);
        request(v1, null);
        request(v2, v1);

        assertThat(processor.processQueue()).isEqualTo(2);

        assertThat(jdbc.queryForList("select title from opportunities", String.class))
                .containsExactly("웹 개발 과정 (개정)");
        assertThat(versionCount()).isEqualTo(1);
        assertThat(requestStatuses()).containsOnly(RawRecordReader.SUCCESS);
    }

    @Test
    void 요청이_없거나_알림을_놓쳐도_대조가_정규화한다() {
        RawExternalRecord course = raw(work24Sync, "[\"AIG1\",\"3\"]", course("AIG1", "3", "웹 개발 과정"), T1);

        assertThat(processor.processQueue()).isZero();
        assertThat(processor.reconcile()).isEqualTo(1);

        assertThat(sourceRepository.findBySourceIdAndExternalId(work24.getId(), "AIG1:3")).isPresent();
        assertThat(stateOutcome(course)).isEqualTo(RawIngestProcessor.NORMALIZED);
        assertThat(processor.reconcile()).isZero();
    }

    @Test
    void 원본_데이터_문제는_FAILED로_버리고_대조에서도_다시_시도하지_않는다() {
        RawExternalRecord noTitle = raw(work24Sync, "[\"AIG1\",\"3\"]", course("AIG1", "3", ""), T1);
        request(noTitle, null);

        assertThat(processor.processQueue()).isEqualTo(1);

        assertThat(requestStatuses()).containsOnly(RawRecordReader.FAILED);
        assertThat(stateOutcome(noTitle)).isEqualTo(RawIngestProcessor.INVALID);
        assertThat(processor.reconcile()).isZero();
    }

    @Test
    void 처리_중_예외로_실패한_요청은_버리고_대조가_다시_정규화한다() {
        RawExternalRecord course = raw(work24Sync, "[\"AIG1\",\"3\"]", course("AIG1", "3", "웹 개발 과정"), T1);
        request(course, null);
        doThrow(new IllegalStateException("일시 오류")).doCallRealMethod().when(normalizer).normalize(any());

        assertThat(processor.processQueue()).isEqualTo(1);
        assertThat(requestStatuses()).containsOnly(RawRecordReader.FAILED);
        assertThat(stateOutcome(course)).isNull();
        assertThat(sourceRepository.findBySourceIdAndExternalId(work24.getId(), "AIG1:3")).isEmpty();

        assertThat(processor.reconcile()).isEqualTo(1);
        assertThat(sourceRepository.findBySourceIdAndExternalId(work24.getId(), "AIG1:3")).isPresent();
        assertThat(stateOutcome(course)).isEqualTo(RawIngestProcessor.NORMALIZED);
    }

    @Test
    void 묶음_트랜잭션에서_한_건이_실패하면_그_묶음만_한_건씩_다시_처리한다() {
        RawExternalRecord good = raw(work24Sync, "[\"AIG1\",\"1\"]", course("AIG1", "1", "정상 과정"), T1);
        RawExternalRecord bad = raw(work24Sync, "[\"AIG1\",\"2\"]", course("AIG1", "2", "실패 과정"), T1);
        request(good, null);
        request(bad, null);
        doAnswer(invocation -> {
            RawRecordRow row = invocation.getArgument(0);
            if (row.externalId().equals(bad.getExternalId())) {
                throw new IllegalStateException("일시 오류");
            }
            return invocation.callRealMethod();
        }).when(normalizer).normalize(any());

        assertThat(processor.processQueue()).isEqualTo(2);

        assertThat(sourceRepository.findBySourceIdAndExternalId(work24.getId(), "AIG1:1")).isPresent();
        assertThat(sourceRepository.findBySourceIdAndExternalId(work24.getId(), "AIG1:2")).isEmpty();
        assertThat(stateOutcome(good)).isEqualTo(RawIngestProcessor.NORMALIZED);
        assertThat(stateOutcome(bad)).isNull();
        assertThat(jdbc.queryForList("select status from raw_normalization_request order by id", String.class))
                .containsExactly(RawRecordReader.SUCCESS, RawRecordReader.FAILED);
    }

    @Test
    void 대조는_같은_원본의_최신_스냅샷만_정규화한다() {
        raw(work24Sync, "[\"AIG1\",\"3\"]", course("AIG1", "3", "웹 개발 과정"), T1);
        RawExternalRecord v2 = raw(work24Sync, "[\"AIG1\",\"3\"]", course("AIG1", "3", "웹 개발 과정 (개정)"), T2);
        raw(work24Sync, "[\"AIG2\",\"1\"]", course("AIG2", "1", "데이터 과정"), T1);

        assertThat(processor.reconcile()).isEqualTo(2);

        assertThat(jdbc.queryForList("select title from opportunities order by title", String.class))
                .containsExactly("데이터 과정", "웹 개발 과정 (개정)");
        assertThat(stateOutcome(v2)).isEqualTo(RawIngestProcessor.NORMALIZED);
        assertThat(processor.reconcile()).isZero();
    }

    @Test
    void 묶음_크기보다_많은_요청도_모두_처리한다() {
        for (int round = 1; round <= 5; round++) {
            request(raw(work24Sync, "[\"AIG1\",\"" + round + "\"]", course("AIG1", String.valueOf(round), "과정 " + round), T1), null);
        }

        assertThat(processor.processQueue()).isEqualTo(5);
        assertThat(requestStatuses()).hasSize(5).containsOnly(RawRecordReader.SUCCESS);
    }

    private RawExternalRecord raw(SyncHistory sync, String externalId, String payload, OffsetDateTime seenAt) {
        RawExternalRecord record = new RawExternalRecord(sync, externalId, "sha256-test:" + payload.hashCode(),
                JSON.readTree(payload), seenAt);
        tx.executeWithoutResult(s -> em.persist(record));
        return record;
    }

    private void request(RawExternalRecord record, RawExternalRecord previous) {
        tx.executeWithoutResult(s -> em.persist(new RawNormalizationRequest(record, previous,
                previous == null ? RecordResultType.CREATED : RecordResultType.UPDATED, T1)));
    }

    private java.util.List<String> requestStatuses() {
        return jdbc.queryForList("select status from raw_normalization_request", String.class);
    }

    private String stateOutcome(RawExternalRecord record) {
        return jdbc.query("select outcome from raw_ingest_states where raw_record_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, record.getId());
    }

    private Integer versionCount() {
        return jdbc.queryForObject("select count(*) from opportunity_versions", Integer.class);
    }

    private static String course(String trprId, String trprDegr, String title) {
        return """
                {"trprId":"%s","trprDegr":"%s","title":"%s","subTitle":"코드 아카데미",
                 "traStartDate":"2026-11-02","traEndDate":"2027-04-30","trainTargetCd":"C0104","trainTarget":"K-디지털 트레이닝",
                 "trngAreaCd":"11530","yardMan":"20","regCourseMan":"3","stdgScor":"0"}
                """.formatted(trprId, trprDegr, title);
    }

    private static String policy(String plcyNo, String plcyNm) {
        return """
                {"plcyNo":"%s","plcyNm":"%s","sprtTrgtMinAge":"19","sprtTrgtMaxAge":"34","sprtTrgtAgeLmtYn":"N",
                 "aplyPrdSeCd":"0057001","aplyYmd":"20261001 ~ 20261031","lclsfNm":"주거","mclsfNm":"주거지원",
                 "frstRegDt":"2026-09-01 10:00:00"}
                """.formatted(plcyNo, plcyNm);
    }
}
