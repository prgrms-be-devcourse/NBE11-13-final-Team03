package com.team3.dataservice.opportunity.conditionset;

import com.team3.dataservice.opportunity.domain.repository.ConditionSetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.team3.dataservice.support.ApplicationPostgresTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건 묶음 ID 확보(resolveId)를 실제 PostgreSQL로 검증한다. 동시 생성을 보려고 테스트 트랜잭션 없이 각자 커밋한다.
 * 클래스 전용 PostgreSQL·application_user로 실행한다 (매핑 테이블은 Hibernate create-drop).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConditionSetRepositoryTest extends ApplicationPostgresTest {

    private static final String HASH = "a".repeat(64);

    @Autowired
    private ConditionSetRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM condition_sets");
    }

    @Test
    void 같은_해시는_한_번만_만들고_같은_ID를_돌려준다() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long first = tx.execute(s -> repository.resolveId(HASH));
        Long second = tx.execute(s -> repository.resolveId(HASH));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM condition_sets", Long.class)).isEqualTo(1L);
    }

    @Test
    void 커밋_전인_같은_해시를_동시에_만들면_기다렸다가_같은_ID를_돌려준다() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Long> first = pool.submit(() -> tx.execute(s -> {
                Long id = repository.resolveId(HASH);
                firstInserted.countDown();
                await(release);
                return id;
            }));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Long> second = pool.submit(() -> tx.execute(s -> repository.resolveId(HASH)));
            Thread.sleep(300);
            assertThat(second.isDone()).as("커밋 전인 같은 해시의 INSERT를 기다린다").isFalse();
            release.countDown();

            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(first.get(30, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM condition_sets", Long.class)).isEqualTo(1L);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
