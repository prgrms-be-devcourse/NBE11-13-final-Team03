package com.team3.backend.architecture;

import com.team3.backend.BackendApplication;
import com.team3.backend.auth.SecurityConfig;
import com.team3.backend.global.config.ApplicationJpaConfig;
import com.team3.backend.notification.boundary.NotificationBoundaryComponent;
import com.team3.backend.notification.boundary.NotificationBoundaryEntity;
import com.team3.backend.notification.boundary.NotificationBoundaryRepository;
import com.team3.backend.notification.config.NotificationJpaConfig;
import com.team3.backend.notification.config.NotificationSecurityConfig;
import com.team3.backend.opportunity.domain.entity.ConditionSet;
import com.team3.backend.opportunity.domain.repository.ConditionSetRepository;
import com.team3.backend.opportunity.ingest.RawIngestState;
import com.team3.backend.preference.domain.entity.UserOpportunityPreference;
import com.team3.backend.preference.domain.repository.UserOpportunityPreferenceRepository;
import com.team3.backend.search.domain.repository.OpportunitySearchRepository;
import com.team3.backend.search.service.OpportunitySearchService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest(
        classes = BackendApplication.class,
        properties = {
                "app.role=api",
                "app.scheduling.enabled=false",
                "yon.normalization.condition-set-backfill.enabled=false"
        }
)
class ApplicationContextBoundaryTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("Application Context는 자기 Component와 Security만 등록한다")
    void componentAndSecurityBoundary() {
        assertThat(context.getBeansOfType(ApplicationJpaConfig.class)).hasSize(1);
        assertThat(context.getBeansOfType(SecurityConfig.class)).hasSize(1);
        assertThat(context.getBeansOfType(OpportunitySearchService.class)).hasSize(1);
        assertThat(context.containsBean("securityFilterChain")).isTrue();

        assertThat(context.getBeansOfType(NotificationJpaConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(NotificationSecurityConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(NotificationBoundaryComponent.class)).isEmpty();
        assertThat(context.containsBean("notificationSecurityFilterChain")).isFalse();
    }

    @Test
    @DisplayName("Application Context는 Application Entity와 Repository만 등록한다")
    void entityAndRepositoryBoundary() {
        assertThat(entityManagerFactory.getMetamodel().entity(RawIngestState.class)).isNotNull();
        assertThat(entityManagerFactory.getMetamodel().entity(UserOpportunityPreference.class)).isNotNull();
        assertThat(entityManagerFactory.getMetamodel().entity(ConditionSet.class)).isNotNull();
        assertThat(context.getBeansOfType(UserOpportunityPreferenceRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(OpportunitySearchRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(ConditionSetRepository.class)).hasSize(1);

        assertThatThrownBy(() -> entityManagerFactory.getMetamodel().entity(NotificationBoundaryEntity.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(context.getBeansOfType(NotificationBoundaryRepository.class)).isEmpty();
    }
}
