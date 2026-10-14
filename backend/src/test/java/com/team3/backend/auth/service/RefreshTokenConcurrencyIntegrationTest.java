package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.RefreshTokenHasher;
import com.team3.backend.auth.redis.RefreshTokenCacheRepository;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class RefreshTokenConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("yon_test")
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

    /*
     * 이번 테스트 대상은 PostgreSQL 잠금이므로
     * Redis는 실제 서버 대신 Mock으로 교체한다.
     */
    @MockitoBean
    private RefreshTokenCacheRepository refreshTokenCacheRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("동일한 Refresh Token으로 동시에 재발급하면 한 요청만 성공한다")
    void onlyOneConcurrentReissueSucceeds() throws Exception {
        User user = userRepository.saveAndFlush(
                User.builder()
                        .kakaoId(12345L)
                        .nickname("테스트 사용자")
                        .email("test@example.com")
                        .role(Role.USER)
                        .build()
        );

        TokenService.TokenPair originalTokenPair =
                tokenService.issueToken(user);

        String originalRefreshToken =
                originalTokenPair.refreshToken();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch readyLatch =
                new CountDownLatch(2);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        Callable<ReissueAttempt> task = () -> {
            readyLatch.countDown();
            startLatch.await();

            try {
                TokenService.TokenPair tokenPair =
                        tokenService.reissueToken(
                                originalRefreshToken
                        );

                return ReissueAttempt.success(tokenPair);

            } catch (BusinessException exception) {
                return ReissueAttempt.failure(
                        (AuthErrorCode) exception.getErrorCode()
                );
            }
        };

        Future<ReissueAttempt> firstFuture =
                executor.submit(task);

        Future<ReissueAttempt> secondFuture =
                executor.submit(task);

        try {
            assertTrue(
                    readyLatch.await(5, TimeUnit.SECONDS),
                    "두 요청이 실행 준비 상태에 도달하지 못했습니다."
            );

            startLatch.countDown();

            ReissueAttempt first =
                    firstFuture.get(10, TimeUnit.SECONDS);

            ReissueAttempt second =
                    secondFuture.get(10, TimeUnit.SECONDS);

            List<ReissueAttempt> attempts =
                    List.of(first, second);

            long successCount = attempts.stream()
                    .filter(ReissueAttempt::isSuccess)
                    .count();

            long mismatchCount = attempts.stream()
                    .filter(attempt ->
                            attempt.errorCode()
                                    == AuthErrorCode.REFRESH_TOKEN_MISMATCH
                    )
                    .count();

            assertEquals(
                    1,
                    successCount,
                    "동시 재발급 요청 중 하나만 성공해야 합니다."
            );

            assertEquals(
                    1,
                    mismatchCount,
                    "나머지 요청은 기존 토큰 불일치로 실패해야 합니다."
            );

            TokenService.TokenPair successfulPair =
                    attempts.stream()
                            .filter(ReissueAttempt::isSuccess)
                            .findFirst()
                            .orElseThrow()
                            .tokenPair();

            RefreshToken storedToken =
                    refreshTokenRepository
                            .findByUserId(user.getId())
                            .orElseThrow();

            assertTrue(
                    refreshTokenHasher.matches(
                            storedToken.getTokenHash(),
                            successfulPair.refreshToken()
                    ),
                    "DB에는 성공한 요청의 새 Refresh Token이 저장돼야 합니다."
            );

        } finally {
            executor.shutdownNow();
        }
    }

    private record ReissueAttempt(
            TokenService.TokenPair tokenPair,
            AuthErrorCode errorCode
    ) {
        static ReissueAttempt success(
                TokenService.TokenPair tokenPair
        ) {
            return new ReissueAttempt(tokenPair, null);
        }

        static ReissueAttempt failure(
                AuthErrorCode errorCode
        ) {
            return new ReissueAttempt(null, errorCode);
        }

        boolean isSuccess() {
            return tokenPair != null;
        }
    }
}