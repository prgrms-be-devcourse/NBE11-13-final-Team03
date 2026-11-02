package com.team3.dataservice.opportunity.service;

import com.team3.dataservice.opportunity.domain.entity.OpportunityCategory;
import com.team3.dataservice.opportunity.domain.repository.OpportunityCategoryRepository;
import com.team3.dataservice.opportunity.domain.repository.OpportunityVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 분류 행(opportunity_categories)이 없는 청년정책 버전에 분류 행을 채운다 (테이블이 생기기 전에 만든 버전).
 * 새로 정규화하는 버전은 정규화가 직접 채운다. 값은 버전 스냅샷의 category.large·medium(원본 문자열)에서 읽어
 * 정규화와 같은 규칙(CategoryNormalizer)으로 나눈다. 재수집·새 버전이 필요 없다.
 *
 * 실행 시점·실패 격리는 ConditionSetBackfill과 같다: 앱이 시작되면 비동기로 한 번, 1,000건 묶음마다 커밋하고
 * 실패한 묶음은 건너뛰어 다음 실행이 다시 처리한다. run()은 언제 다시 불러도 안전하다 (행 없는 버전만 채움).
 * 분류가 비어 행이 0개인 버전은 다음 실행에서도 다시 읽지만 쓰지 않는다.
 * 버전과 그 분류 행은 정규화의 같은 트랜잭션에서 커밋되므로 정규화와 동시에 돌아도 같은 버전을 두 번 채우지 않는다.
 *
 * yon.normalization.category-backfill.enabled=false면 끈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yon.normalization.category-backfill.enabled", havingValue = "true", matchIfMissing = true)
public class YouthPolicyCategoryBackfill {

    private static final String MISSING = """
            select v.id, v.normalized_snapshot #>> '{category,large}', v.normalized_snapshot #>> '{category,medium}'
            from opportunity_versions v
            join opportunities o on o.id = v.opportunity_id
            where o.opportunity_type = 'YOUTH_POLICY' and v.id > ?
              and not exists (select 1 from opportunity_categories c where c.version_id = v.id)
            order by v.id
            limit ?""";

    /** 묶음 크기 (테스트에서 바꿀 수 있게 필드로 둔다) */
    private int chunkSize = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final OpportunityVersionRepository versionRepository;
    private final OpportunityCategoryRepository categoryRepository;
    private final PlatformTransactionManager transactionManager;

    private record Missing(long versionId, String large, String medium) {
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("Youth policy category backfill failed (검색·추천의 다중 분류 매칭에서 일부 정책이 빠질 수 있음)", e);
        }
    }

    /** @return 분류 행을 만든 버전 수 */
    public int run() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long started = System.nanoTime();
        int filled = 0;
        int failedChunks = 0;
        long afterId = 0L;
        while (true) {
            List<Missing> chunk = jdbcTemplate.query(MISSING,
                    (rs, i) -> new Missing(rs.getLong(1), rs.getString(2), rs.getString(3)), afterId, chunkSize);
            if (chunk.isEmpty()) {
                break;
            }
            try {
                Integer count = tx.execute(status -> fill(chunk));
                filled += count == null ? 0 : count;
            } catch (RuntimeException e) {
                failedChunks++;
                log.warn("Youth policy category backfill chunk failed, skipping (다음 실행이 다시 처리): versionIds={}..{}",
                        chunk.getFirst().versionId(), chunk.getLast().versionId(), e);
            }
            afterId = chunk.getLast().versionId();
            if (chunk.size() < chunkSize) {
                break;
            }
        }
        if (filled > 0 || failedChunks > 0) {
            log.info("Youth policy categories backfilled: versions={}, failedChunks={}, elapsedMs={}", filled, failedChunks,
                    (System.nanoTime() - started) / 1_000_000);
        }
        return filled;
    }

    private int fill(List<Missing> chunk) {
        List<OpportunityCategory> rows = new ArrayList<>();
        int filled = 0;
        for (Missing missing : chunk) {
            List<OpportunityCategory> categories = YouthPolicyNormalizationService.categories(
                    versionRepository.getReferenceById(missing.versionId()), missing.large(), missing.medium());
            if (!categories.isEmpty()) {
                rows.addAll(categories);
                filled++;
            }
        }
        categoryRepository.saveAll(rows);
        return filled;
    }
}
