package com.team3.backend.user.service;

import org.junit.jupiter.api.Tag;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.eligibility.domain.entity.EducationLevel;
import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.region.domain.repository.RegionRepository;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.domain.entity.UserInterest;
import com.team3.backend.interest.domain.repository.UserInterestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
@ActiveProfiles("test")
@SpringBootTest
@Tag("postgres")
class UserWithdrawalIntegrationTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("yon_withdrawal_test")
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
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private EligibilityProfileRepository profileRepository;

    @Autowired
    private EligibilityResultRepository resultRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private UserInterestRepository userInterestRepository;

    @Test
    void 회원_탈퇴시_사용자와_연결된_데이터를_실제_DB에서_삭제한다() {
        Region seoul = regionRepository.saveAndFlush(
                Region.builder()
                        .regionCode("1100000000")
                        .regionName("서울특별시")
                        .build()
        );

        Region gangnam = regionRepository.saveAndFlush(
                Region.builder()
                        .regionCode("1168000000")
                        .regionName("강남구")
                        .parent(seoul)
                        .build()
        );

        User user = userRepository.saveAndFlush(
                User.builder()
                        .kakaoId(123456789L)
                        .nickname("탈퇴 테스트 사용자")
                        .email("withdrawal@example.com")
                        .role(Role.USER)
                        .build()
        );

        Long userId = user.getId();

        userInterestRepository.saveAndFlush(
                UserInterest.create(
                        user,
                        InterestType.CATEGORY,
                        "일자리"
                )
        );

        userInterestRepository.saveAndFlush(
                UserInterest.create(
                        user,
                        InterestType.NCS,
                        "20010101"
                )
        );

        refreshTokenRepository.saveAndFlush(
                RefreshToken.builder()
                        .user(user)
                        .sessionId(UUID.randomUUID().toString())
                        .deviceId(UUID.randomUUID().toString())
                        .tokenHash("a".repeat(64))
                        .expiresAt(
                                LocalDateTime.now().plusDays(7)
                        )
                        .lastUsedAt(LocalDateTime.now())
                        .build()
        );

        profileRepository.saveAndFlush(
                EligibilityProfile.builder()
                        .userId(userId)
                        .birthDate(
                                LocalDate.of(1998, 5, 15)
                        )
                        .residenceRegion(gangnam)
                        .employmentStatus(
                                EmploymentStatus.UNEMPLOYED
                        )
                        .educationLevel(
                                EducationLevel.UNIVERSITY
                        )
                        .build()
        );

        resultRepository.saveAndFlush(
                EligibilityResult.builder()
                        .userId(userId)
                        .conditionSetId(300L)
                        .profileVersion(1L)
                        .ruleVersion("v1")
                        .eligibilityStatus(
                                EligibilityStatus.ELIGIBLE
                        )
                        .reason("모든 조건 충족")
                        .conditionResults(List.of())
                        .validUntil(
                                LocalDate.now().plusDays(1)
                        )
                        .evaluatedAt(LocalDateTime.now())
                        .build()
        );

        userService.withdraw(userId);

        assertThat(
                userRepository.existsById(userId)
        ).isFalse();

        assertThat(
                refreshTokenRepository.findAllByUserId(userId)
        ).isEmpty();

        assertThat(
                profileRepository.findByUserId(userId)
        ).isEmpty();

        assertThat(
                resultRepository.findFreshness(
                        userId,
                        List.of(300L)
                )
        ).isEmpty();

        assertThat(
                userInterestRepository
                        .findAllByUserIdOrderByInterestTypeAscInterestCodeAsc(userId)
        ).isEmpty();
    }
}