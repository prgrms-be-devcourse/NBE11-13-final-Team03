package com.team3.backend.auth.service;

import org.junit.jupiter.api.Tag;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.RefreshTokenHasher;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
@ActiveProfiles("test")
@SpringBootTest
@Tag("postgres")
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
                tokenService.issueToken(
                        user,
                        "test-device-id"
                );

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
                            .findByUserIdAndDeviceId(
                                    user.getId(),
                                    "test-device-id"
                            )
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

    @Test
    @DisplayName("동일 사용자가 서로 다른 기기에서 동시에 로그인해도 세션이 각각 저장된다")
    void concurrentLoginsFromDifferentDevicesCreateSeparateSessions()
            throws Exception {

        User user = userRepository.saveAndFlush(
                User.builder()
                        .kakaoId(67890L)
                        .nickname("동시 로그인 사용자")
                        .email("concurrent@example.com")
                        .role(Role.USER)
                        .build()
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch readyLatch =
                new CountDownLatch(2);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        Callable<TokenService.TokenPair> firstLoginTask = () -> {
            readyLatch.countDown();
            startLatch.await();

            return tokenService.issueToken(
                    user,
                    "device-1"
            );
        };

        Callable<TokenService.TokenPair> secondLoginTask = () -> {
            readyLatch.countDown();
            startLatch.await();

            return tokenService.issueToken(
                    user,
                    "device-2"
            );
        };

        Future<TokenService.TokenPair> firstFuture =
                executor.submit(firstLoginTask);

        Future<TokenService.TokenPair> secondFuture =
                executor.submit(secondLoginTask);

        try {
            assertTrue(
                    readyLatch.await(5, TimeUnit.SECONDS),
                    "두 로그인 요청이 실행 준비 상태에 도달하지 못했습니다."
            );

            startLatch.countDown();

            TokenService.TokenPair firstTokenPair =
                    firstFuture.get(10, TimeUnit.SECONDS);

            TokenService.TokenPair secondTokenPair =
                    secondFuture.get(10, TimeUnit.SECONDS);

            assertNotNull(firstTokenPair);
            assertNotNull(secondTokenPair);

            assertNotEquals(
                    firstTokenPair.refreshToken(),
                    secondTokenPair.refreshToken(),
                    "서로 다른 로그인 세션은 서로 다른 Refresh Token을 발급해야 합니다."
            );

            List<RefreshToken> sessions =
                    refreshTokenRepository
                            .findAllByUserId(user.getId());

            assertEquals(
                    2,
                    sessions.size(),
                    "두 기기의 로그인 세션이 모두 저장돼야 합니다."
            );

            RefreshToken firstSession = sessions.stream()
                    .filter(session ->
                            "device-1".equals(session.getDeviceId())
                    )
                    .findFirst()
                    .orElseThrow();

            RefreshToken secondSession = sessions.stream()
                    .filter(session ->
                            "device-2".equals(session.getDeviceId())
                    )
                    .findFirst()
                    .orElseThrow();

            assertNotEquals(
                    firstSession.getSessionId(),
                    secondSession.getSessionId(),
                    "기기마다 별도의 sessionId가 생성돼야 합니다."
            );

            assertTrue(
                    refreshTokenHasher.matches(
                            firstSession.getTokenHash(),
                            firstTokenPair.refreshToken()
                    ),
                    "첫 번째 기기의 Refresh Token이 DB 세션과 일치해야 합니다."
            );

            assertTrue(
                    refreshTokenHasher.matches(
                            secondSession.getTokenHash(),
                            secondTokenPair.refreshToken()
                    ),
                    "두 번째 기기의 Refresh Token이 DB 세션과 일치해야 합니다."
            );

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("동일 기기에서 동시에 로그인하면 하나의 세션만 유지하고 마지막 토큰만 유효하다")
    void concurrentLoginsFromSameDeviceKeepSingleSession()
            throws Exception {

        User user = userRepository.saveAndFlush(
                User.builder()
                        .kakaoId(98765L)
                        .nickname("동일 기기 사용자")
                        .email("same-device@example.com")
                        .role(Role.USER)
                        .build()
        );

        String deviceId = "same-device-id";

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch readyLatch =
                new CountDownLatch(2);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        Callable<TokenService.TokenPair> loginTask = () -> {
            readyLatch.countDown();
            startLatch.await();

            return tokenService.issueToken(
                    user,
                    deviceId
            );
        };

        Future<TokenService.TokenPair> firstFuture =
                executor.submit(loginTask);

        Future<TokenService.TokenPair> secondFuture =
                executor.submit(loginTask);

        try {
            assertTrue(
                    readyLatch.await(5, TimeUnit.SECONDS),
                    "두 로그인 요청이 실행 준비 상태에 도달하지 못했습니다."
            );

            startLatch.countDown();

            TokenService.TokenPair firstTokenPair =
                    firstFuture.get(10, TimeUnit.SECONDS);

            TokenService.TokenPair secondTokenPair =
                    secondFuture.get(10, TimeUnit.SECONDS);

            assertNotEquals(
                    firstTokenPair.refreshToken(),
                    secondTokenPair.refreshToken(),
                    "각 로그인 요청은 서로 다른 Refresh Token을 발급해야 합니다."
            );

            List<RefreshToken> sessions =
                    refreshTokenRepository
                            .findAllByUserId(user.getId());

            assertEquals(
                    1,
                    sessions.size(),
                    "같은 기기의 동시 로그인은 하나의 세션만 유지해야 합니다."
            );

            RefreshToken storedSession = sessions.getFirst();

            assertEquals(
                    deviceId,
                    storedSession.getDeviceId()
            );

            boolean firstTokenMatches =
                    refreshTokenHasher.matches(
                            storedSession.getTokenHash(),
                            firstTokenPair.refreshToken()
                    );

            boolean secondTokenMatches =
                    refreshTokenHasher.matches(
                            storedSession.getTokenHash(),
                            secondTokenPair.refreshToken()
                    );

            assertNotEquals(
                    firstTokenMatches,
                    secondTokenMatches,
                    "두 토큰 중 마지막으로 저장된 하나만 유효해야 합니다."
            );

            assertTrue(
                    firstTokenMatches || secondTokenMatches,
                    "DB에는 두 로그인 요청 중 하나의 Refresh Token이 저장돼야 합니다."
            );

        } finally {
            executor.shutdownNow();
        }
    }
}