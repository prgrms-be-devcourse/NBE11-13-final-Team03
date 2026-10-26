package com.team3.backend.tracking.domain.repository;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.PersonalizationStatus;
import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.entity.OpportunityEventProcessingStatus;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
@ActiveProfiles("test")
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class OpportunityEventRepositoryTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("yon_tracking_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );

        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );

        registry.add(
                "spring.datasource.driver-class-name",
                postgres::getDriverClassName
        );
    }

    private static final Instant NOW =
            Instant.parse("2026-10-20T02:00:00Z");

    @Autowired
    private OpportunityEventRepository eventRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 처리_시각이_도래한_PENDING_이벤트만_조회한다() {
        OpportunityVersion version = persistOpportunityVersion();

        OpportunityEvent ready = event(
                version,
                "APPLICATION_OPENED:1:2026-10-20"
        );

        OpportunityEvent delayed = event(
                version,
                "DEADLINE_SOON:1:D-3:2026-10-23"
        );

        delayed.scheduleRetry(
                NOW,
                Duration.ofMinutes(5),
                "임시 오류"
        );

        eventRepository.save(ready);
        eventRepository.save(delayed);
        entityManager.flush();

        List<Long> foundIds =
                eventRepository.findReadyEventIds(
                        OpportunityEventProcessingStatus.PENDING,
                        NOW,
                        PageRequest.of(0, 10)
                );

        assertThat(foundIds)
                .containsExactly(ready.getId());
    }

    @Test
    void 첫번째_Worker만_이벤트_점유에_성공한다() {
        OpportunityVersion version = persistOpportunityVersion();

        OpportunityEvent event = event(
                version,
                "APPLICATION_OPENED:1:2026-10-20"
        );

        eventRepository.save(event);
        entityManager.flush();

        Long eventId = event.getId();

        boolean firstClaim =
                eventRepository.claim(eventId, NOW);

        boolean secondClaim =
                eventRepository.claim(eventId, NOW);

        OpportunityEvent found =
                entityManager.find(
                        OpportunityEvent.class,
                        eventId
                );

        assertThat(firstClaim).isTrue();
        assertThat(secondClaim).isFalse();

        assertThat(found.getProcessingStatus())
                .isEqualTo(
                        OpportunityEventProcessingStatus.PROCESSING
                );

        assertThat(found.getProcessingStartedAt())
                .isEqualTo(NOW);
    }

    @Test
    void 처리_예정_시각_전에는_이벤트를_점유할_수_없다() {
        OpportunityVersion version = persistOpportunityVersion();

        OpportunityEvent event = event(
                version,
                "DEADLINE_SOON:1:D-3:2026-10-23"
        );

        event.scheduleRetry(
                NOW,
                Duration.ofMinutes(5),
                "임시 오류"
        );

        eventRepository.save(event);
        entityManager.flush();

        Long eventId = event.getId();

        boolean beforeRetryAt =
                eventRepository.claim(eventId, NOW);

        boolean afterRetryAt =
                eventRepository.claim(
                        eventId,
                        NOW.plus(Duration.ofMinutes(5))
                );

        assertThat(beforeRetryAt).isFalse();
        assertThat(afterRetryAt).isTrue();
    }

    @Test
    void 조회_개수는_Pageable로_제한한다() {
        OpportunityVersion version = persistOpportunityVersion();

        for (int i = 1; i <= 5; i++) {
            eventRepository.save(
                    event(
                            version,
                            "APPLICATION_OPENED:"
                                    + i
                                    + ":2026-10-20"
                    )
            );
        }

        entityManager.flush();

        List<Long> foundIds =
                eventRepository.findReadyEventIds(
                        OpportunityEventProcessingStatus.PENDING,
                        NOW,
                        PageRequest.of(0, 2)
                );

        assertThat(foundIds).hasSize(2);
    }

    private OpportunityEvent event(
            OpportunityVersion version,
            String dedupKey
    ) {
        return OpportunityEvent.create(
                version.getOpportunity(),
                version,
                OpportunityEventType.APPLICATION_OPENED,
                LocalDate.of(2026, 10, 20),
                dedupKey,
                Map.of(
                        "startDate",
                        "2026-10-20"
                ),
                true,
                null,
                NOW
        );
    }

    private OpportunityVersion persistOpportunityVersion() {
        Opportunity opportunity =
                Opportunity.builder()
                        .opportunityType(
                                OpportunityType.YOUTH_POLICY
                        )
                        .title("청년 지원 정책")
                        .build();

        entityManager.persist(opportunity);

        OpportunityVersion version =
                OpportunityVersion.builder()
                        .opportunity(opportunity)
                        .versionNo(1)
                        .normalizedSnapshot(Map.of())
                        .personalizationStatus(
                                PersonalizationStatus.UNKNOWN
                        )
                        .normalizationVersion("v1")
                        .build();

        entityManager.persist(version);
        opportunity.changeCurrentVersion(version);

        entityManager.flush();

        return version;
    }
}
