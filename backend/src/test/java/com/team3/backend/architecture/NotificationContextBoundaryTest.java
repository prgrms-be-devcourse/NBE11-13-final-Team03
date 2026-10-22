package com.team3.backend.architecture;

import com.team3.backend.auth.SecurityConfig;
import com.team3.backend.collection.service.CollectionScheduler;
import com.team3.backend.config.ApplicationJpaConfig;
import com.team3.backend.notification.NotificationApplication;
import com.team3.backend.notification.boundary.NotificationBoundaryComponent;
import com.team3.backend.notification.boundary.NotificationBoundaryEntity;
import com.team3.backend.notification.boundary.NotificationBoundaryRepository;
import com.team3.backend.notification.config.NotificationJpaConfig;
import com.team3.backend.notification.config.NotificationSecurityConfig;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.search.domain.repository.OpportunitySearchRepository;
import com.team3.backend.search.service.OpportunitySearchService;
import com.team3.backend.user.domain.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(
        classes = NotificationApplication.class,
        properties = "app.role=notification"
)
@AutoConfigureMockMvc
class NotificationContextBoundaryTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Notification Context는 자기 Component와 Security만 등록한다")
    void componentAndSecurityBoundary() {
        assertThat(context.getBeansOfType(NotificationJpaConfig.class)).hasSize(1);
        assertThat(context.getBeansOfType(NotificationSecurityConfig.class)).hasSize(1);
        assertThat(context.getBeansOfType(NotificationBoundaryComponent.class)).hasSize(1);
        assertThat(context.containsBean("notificationSecurityFilterChain")).isTrue();

        assertThat(context.getBeansOfType(ApplicationJpaConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(SecurityConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(OpportunitySearchService.class)).isEmpty();
        assertThat(context.getBeansOfType(CollectionScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
        assertThat(context.containsBean("securityFilterChain")).isFalse();
    }

    @Test
    @DisplayName("Notification Context는 Notification Entity와 Repository만 등록한다")
    void entityAndRepositoryBoundary() {
        assertThat(entityManagerFactory.getMetamodel().entity(NotificationBoundaryEntity.class)).isNotNull();
        assertThat(context.getBeansOfType(NotificationBoundaryRepository.class)).hasSize(1);

        assertThatThrownBy(() -> entityManagerFactory.getMetamodel().entity(Opportunity.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(context.getBeansOfType(UserRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(OpportunitySearchRepository.class)).isEmpty();
    }

    @Test
    @DisplayName("Notification 서버는 내부 인증 계약 구현 전까지 모든 요청을 거부한다")
    void deniesRequestsByDefault() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("클래스패스의 모든 Notification Entity와 Repository가 등록되고 Application은 제외된다")
    void allPersistenceTypesRespectBoundary() {
        PersistenceBoundaryAssertions.assertComplete(context, entityManagerFactory, true);
    }
}
