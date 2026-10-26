package com.team3.dataservice.opportunity.ingest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 수집 원본 → 정규화 처리기.
 *
 * 1. 정제 요청 처리 (processQueue): NOTIFY를 받거나 짧은 주기 폴링 때 실행. PENDING 요청을 선점해 처리하고 SUCCESS/FAILED로 바꾼다.
 *    실패한 요청은 다시 시도하지 않는다 (2번이 복구한다). 최신 원본이 아니거나 이미 처리한 해시면 정규화 없이 SUCCESS.
 * 2. 주기적 대조 (reconcile): 긴 주기로 (출처, 외부 ID)별 최신 원본 해시와 raw_ingest_states의 해시를 비교해 다른 것만 정규화한다.
 *    알림 누락·요청 실패·처리 중 종료를 모두 여기서 복구한다. 데이터 기준은 이쪽이다.
 *
 * 트랜잭션: 원본 transactionSize건을 트랜잭션 1개(REQUIRES_NEW)로 정규화하고 처리 상태를 함께 기록한다.
 * 커밋(WAL fsync) 대기가 첫 적재 시간의 83%여서 묶는다 (ingest 벤치마크 2026-10-20). 묶음 안의 한 건이 예외를 내면 그 묶음 전체가
 * 롤백되고, 그 묶음만 1건씩 다시 처리해 나머지는 저장한다. 원본마다 flush·clear 해서 영속성 컨텍스트가 묶음 크기만큼 쌓이지 않게 한다.
 *
 * 한 애플리케이션 안에서는 묶음 단위로 직렬화한다 (같은 원본을 동시에 정규화하지 않도록). 여러 인스턴스는 SKIP LOCKED로 요청을 나눈다.
 */
@Slf4j
@Service
public class RawIngestProcessor {

    public static final String NORMALIZED = "NORMALIZED";
    public static final String INVALID = "INVALID";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RawRecordReader reader;
    private final RawRecordNormalizer normalizer;
    private final TransactionTemplate claimTx;
    private final TransactionTemplate recordTx;
    private final int batchSize;
    private int transactionSize;
    private final boolean asyncCommit;
    private final ReentrantLock lock = new ReentrantLock();

    @PersistenceContext
    private EntityManager em;

    public RawIngestProcessor(RawRecordReader reader, RawRecordNormalizer normalizer,
                              PlatformTransactionManager transactionManager,
                              @Value("${yon.normalization.ingest.batch-size:100}") int batchSize,
                              @Value("${yon.normalization.ingest.transaction-size:100}") int transactionSize,
                              @Value("${yon.normalization.ingest.async-commit:false}") boolean asyncCommit) {
        this.reader = reader;
        this.normalizer = normalizer;
        this.claimTx = new TransactionTemplate(transactionManager);
        this.recordTx = new TransactionTemplate(transactionManager);
        this.recordTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchSize = Math.max(1, batchSize);
        this.transactionSize = Math.max(1, transactionSize);
        this.asyncCommit = asyncCommit;
    }

    /** async-commit이 켜져 있으면 현재 트랜잭션의 커밋이 WAL fsync를 기다리지 않게 한다 (RawRecordReader.relaxCommitDurability) */
    private void relaxCommit() {
        if (asyncCommit) {
            reader.relaxCommitDurability();
        }
    }

    /** 벤치마크에서 트랜잭션 크기를 바꿔 비교할 때만 쓴다 */
    void changeTransactionSize(int transactionSize) {
        this.transactionSize = Math.max(1, transactionSize);
    }

    /** 한 번에 읽는 건수. 트랜잭션 크기보다 작으면 묶음이 안 차므로 둘 중 큰 값 */
    private int fetchSize() {
        return Math.max(batchSize, transactionSize);
    }

    /** PENDING 요청이 없을 때까지 묶음 단위로 처리한다. @return 처리한 요청 수 */
    public int processQueue() {
        int total = 0;
        int claimed;
        int limit = fetchSize();
        do {
            lock.lock();
            try {
                Integer count = claimTx.execute(status -> {
                    relaxCommit();
                    return processClaimedBatch(limit);
                });
                claimed = count == null ? 0 : count;
            } finally {
                lock.unlock();
            }
            total += claimed;
        } while (claimed == limit);
        if (total > 0) {
            log.info("Normalization requests processed: {}", total);
        }
        return total;
    }

    private int processClaimedBatch(int limit) {
        List<RawRecordRow> rows = reader.claimPending(limit);
        List<Long> succeeded = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        List<RawRecordRow> targets = new ArrayList<>();
        for (RawRecordRow row : rows) {
            if (!row.latest() || row.alreadyProcessed()) {
                succeeded.add(row.requestId());
            } else {
                targets.add(row);
            }
        }
        List<Boolean> results = processAll(targets);
        for (int i = 0; i < targets.size(); i++) {
            (results.get(i) ? succeeded : failed).add(targets.get(i).requestId());
        }
        reader.markStatus(succeeded, RawRecordReader.SUCCESS);
        reader.markStatus(failed, RawRecordReader.FAILED);
        return rows.size();
    }

    /** 처리 기록이 없거나 해시가 다른 최신 원본을 모두 정규화한다. @return 정규화를 시도한 원본 수 */
    public int reconcile() {
        int total = 0;
        long afterId = 0;
        int limit = fetchSize();
        List<RawRecordRow> rows;
        do {
            lock.lock();
            try {
                rows = reader.findOutOfSync(afterId, limit);
                processAll(rows);
            } finally {
                lock.unlock();
            }
            if (!rows.isEmpty()) {
                afterId = rows.getLast().rawRecordId();
            }
            total += rows.size();
        } while (rows.size() == limit);
        if (total > 0) {
            log.info("Raw records reconciled: {}", total);
        }
        return total;
    }

    /** transactionSize건씩 트랜잭션 1개로 처리한다. @return rows와 같은 순서의 결과 (정규화했으면 true) */
    private List<Boolean> processAll(List<RawRecordRow> rows) {
        List<Boolean> results = new ArrayList<>(rows.size());
        for (int from = 0; from < rows.size(); from += transactionSize) {
            List<RawRecordRow> chunk = rows.subList(from, Math.min(from + transactionSize, rows.size()));
            results.addAll(chunk.size() == 1 ? List.of(processOne(chunk.getFirst())) : processChunk(chunk));
        }
        return results;
    }

    private List<Boolean> processChunk(List<RawRecordRow> chunk) {
        try {
            return recordTx.execute(status -> {
                relaxCommit();
                return chunk.stream().map(this::normalizeAndRecord).toList();
            });
        } catch (RuntimeException e) {
            log.warn("Raw record chunk failed, retrying one by one: size={}, cause={}", chunk.size(), e.toString());
            return chunk.stream().map(this::processOne).toList();
        }
    }

    /** 원본 한 건을 트랜잭션 1개로 처리한다. 예외로 실패하면 처리 상태를 남기지 않아 다음 대조 때 다시 시도된다 */
    private boolean processOne(RawRecordRow row) {
        try {
            Boolean normalized = recordTx.execute(status -> {
                relaxCommit();
                return normalizeAndRecord(row);
            });
            return Boolean.TRUE.equals(normalized);
        } catch (RuntimeException e) {
            log.warn("Raw record normalization failed: rawRecordId={}, source={}, externalId={}",
                    row.rawRecordId(), row.sourceCode(), row.externalId(), e);
            return false;
        }
    }

    /** 현재 트랜잭션 안에서 정규화하고 처리 상태를 기록한다. @return 정규화했으면 true, 원본 데이터 문제면 false */
    private boolean normalizeAndRecord(RawRecordRow row) {
        RawRecordNormalizer.Outcome outcome = normalizer.normalize(row);
        reader.saveState(row, outcome == RawRecordNormalizer.Outcome.NORMALIZED ? NORMALIZED : INVALID,
                OffsetDateTime.now(KST));
        em.flush();
        em.clear();
        return outcome == RawRecordNormalizer.Outcome.NORMALIZED;
    }
}
