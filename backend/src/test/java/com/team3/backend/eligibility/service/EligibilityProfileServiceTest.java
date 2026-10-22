package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.dto.EligibilityProfilePatchRequest;
import com.team3.backend.eligibility.dto.EligibilityProfileResponse;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.eligibility.dto.ProfileAnswers;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({
        EligibilityProfileService.class,
        EligibilityProfileValidator.class,
        RegionDirectory.class
})
class EligibilityProfileServiceTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("yon_profile_test")
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
        registry.add(
                "spring.jpa.hibernate.ddl-auto",
                () -> "create-drop"
        );
    }

    @Autowired
    private EligibilityProfileService service;

    @Autowired
    private RegionDirectory regionDirectory;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void setUp() {
        Region seoul = Region.builder()
                .regionCode("1100000000")
                .regionName("서울특별시")
                .build();

        em.persist(seoul);

        em.persist(
                Region.builder()
                        .regionCode("1168000000")
                        .regionName("강남구")
                        .parent(seoul)
                        .build()
        );

        em.persist(
                Region.builder()
                        .regionCode("1144000000")
                        .regionName("마포구")
                        .parent(seoul)
                        .build()
        );

        em.flush();
        regionDirectory.refresh();
    }

    @Test
    void 없으면_만들고_있으면_전체_교체한다() {
        EligibilityProfileResponse created = service.put(
                1L,
                request(
                        "1168000000",
                        new ProfileAnswers.SpecialTargets(
                                AnswerStatus.PROVIDED,
                                Set.of(SpecialTarget.WOMAN)
                        )
                )
        );

        assertThat(created.residenceRegionCode())
                .isEqualTo("1168000000");
        assertThat(created.updatedAt())
                .isNotNull();

        service.put(
                1L,
                request("1144000000", null)
        );

        em.clear();

        EligibilityProfileResponse found =
                service.get(1L);

        assertThat(found.residenceRegionCode())
                .isEqualTo("1144000000");
        assertThat(found.specialTargets())
                .isNull();
    }

    @Test
    void 프로필이_없으면_조회_실패() {
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(
                        EligibilityErrorCode.PROFILE_NOT_FOUND
                );
    }

    @Test
    void 사전에_없거나_시도_코드인_거주지는_거부한다() {
        assertThatThrownBy(() ->
                service.put(
                        1L,
                        request("9999999999", null)
                )
        )
                .extracting("errorCode")
                .isEqualTo(
                        EligibilityErrorCode.INVALID_REGION_CODE
                );

        assertThatThrownBy(() ->
                service.put(
                        1L,
                        request("1100000000", null)
                )
        )
                .extracting("errorCode")
                .isEqualTo(
                        EligibilityErrorCode.INVALID_REGION_CODE
                );
    }

    @Test
    void PATCH는_보내지_않은_필드를_유지한다() {
        service.put(
                1L,
                request(
                        "1168000000",
                        new ProfileAnswers.SpecialTargets(
                                AnswerStatus.PROVIDED,
                                Set.of(SpecialTarget.WOMAN)
                        )
                )
        );

        EligibilityProfileResponse patched =
                service.patch(
                        1L,
                        patch(
                                Optional.of("1144000000"),
                                null
                        )
                );

        assertThat(patched.residenceRegionCode())
                .isEqualTo("1144000000");
        assertThat(patched.specialTargets().targets())
                .containsExactly(SpecialTarget.WOMAN);
        assertThat(patched.birthDate())
                .isEqualTo(LocalDate.of(1998, 5, 15));
    }

    @Test
    void PATCH에서_명시적_null은_미입력으로_바꾼다() {
        service.put(
                1L,
                request(
                        "1168000000",
                        new ProfileAnswers.SpecialTargets(
                                AnswerStatus.PROVIDED,
                                Set.of(SpecialTarget.WOMAN)
                        )
                )
        );

        EligibilityProfileResponse patched =
                service.patch(
                        1L,
                        patch(
                                null,
                                Optional.empty()
                        )
                );

        assertThat(patched.residenceRegionCode())
                .isEqualTo("1168000000");
        assertThat(patched.specialTargets())
                .isNull();
    }

    @Test
    void 프로필이_없으면_PATCH_실패() {
        assertThatThrownBy(() ->
                service.patch(
                        99L,
                        patch(
                                Optional.of("1144000000"),
                                null
                        )
                )
        )
                .extracting("errorCode")
                .isEqualTo(
                        EligibilityErrorCode.PROFILE_NOT_FOUND
                );
    }

    @Test
    void 판정_입력이_실제로_바뀔_때만_profileVersion이_오른다() {
        service.put(
                1L,
                request("1168000000", null)
        );

        assertThat(profileVersion(1L))
                .isEqualTo(1L);

        service.put(
                1L,
                request("1168000000", null)
        );

        assertThat(profileVersion(1L))
                .isEqualTo(1L);

        service.patch(
                1L,
                patch(
                        Optional.of("1168000000"),
                        null
                )
        );

        assertThat(profileVersion(1L))
                .isEqualTo(1L);

        service.put(
                1L,
                request("1144000000", null)
        );

        assertThat(profileVersion(1L))
                .isEqualTo(2L);
    }

    @Test
    void PATCH에서_필수_필드에_null을_보내면_수정에_실패한다() {
        service.put(
                1L,
                request("1168000000", null)
        );

        EligibilityProfilePatchRequest patchRequest =
                new EligibilityProfilePatchRequest(
                        Optional.empty(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThatThrownBy(() ->
                service.patch(1L, patchRequest)
        )
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(
                        EligibilityErrorCode.INVALID_PROFILE_VALUE
                );
    }

    private Long profileVersion(Long userId) {
        em.flush();
        em.clear();

        return em.createQuery(
                        """
                        select p.profileVersion
                        from EligibilityProfile p
                        where p.userId = :userId
                        """,
                        Long.class
                )
                .setParameter("userId", userId)
                .getSingleResult();
    }

    private EligibilityProfilePatchRequest patch(
            Optional<String> residenceCode,
            Optional<ProfileAnswers.SpecialTargets> specialTargets
    ) {
        return new EligibilityProfilePatchRequest(
                null,
                residenceCode,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                specialTargets,
                null,
                null,
                null,
                null
        );
    }

    private EligibilityProfileUpdateRequest request(
            String residenceCode,
            ProfileAnswers.SpecialTargets specialTargets
    ) {
        return new EligibilityProfileUpdateRequest(
                LocalDate.of(1998, 5, 15),
                residenceCode,
                EmploymentStatus.UNEMPLOYED,
                EducationLevel.UNIVERSITY,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                specialTargets,
                null,
                null,
                null,
                null
        );
    }
}