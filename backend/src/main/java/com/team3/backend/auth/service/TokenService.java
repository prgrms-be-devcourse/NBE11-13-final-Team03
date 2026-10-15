package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.*;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenRepository refreshTokenRepository;

    public record TokenPair(
            String accessToken,
            String refreshToken
    ) {}

    // 토큰을 발급하는 서비스 메서드
    @Transactional
    public TokenPair issueToken(User user) {
        TokenPair tokenPair = generateTokenPair(user);

        saveOrUpdateRefreshToken(
                user,
                tokenPair.refreshToken()
        );

        return tokenPair;
    }

    // access token을 갱신하는 서비스 메서드
    // refresh 탈취 문제를 방지하기 위해 갱신 시 access token과 refresh token 줄 다 새로 발급
    @Transactional
    public TokenPair reissueToken(String refreshToken) {

        validateRefreshToken(refreshToken);

        Long userId =
                tokenProvider.getUserId(refreshToken);

        RefreshToken storedToken =
                getAndValidateStoredRefreshTokenForUpdate(
                        userId,
                        refreshToken
                );

        TokenPair newTokenPair =
                generateTokenPair(storedToken.getUser());

        rotateRefreshToken(
                storedToken,
                newTokenPair.refreshToken()
        );

        return newTokenPair;
    }

    // 토큰 발급 비즈니스 로직
    public TokenPair generateTokenPair(User user) {
        String accessToken = tokenProvider.generateToken(
                user,
                jwtProperties.getAccessTokenValidity(),
                TokenType.ACCESS
        );
        String refreshToken = tokenProvider.generateToken(
                user,
                jwtProperties.getRefreshTokenValidity(),
                TokenType.REFRESH
        );
        return new TokenPair(
                accessToken,
                refreshToken
        );
    }

    // 토큰을 저장 또는 갱신하는 코드
    private void saveOrUpdateRefreshToken(
            User user,
            String refreshToken
    ) {
        LocalDateTime now = LocalDateTime.now();

        String tokenHash =
                refreshTokenHasher.hash(refreshToken);

        LocalDateTime expiresAt =
                now.plus(jwtProperties.getRefreshTokenValidity());

        Optional<RefreshToken> existingToken =
                refreshTokenRepository.findByUserId(user.getId());

        if (existingToken.isPresent()) {
            existingToken.get().rotate(tokenHash, expiresAt);
        } else {
            RefreshToken newToken =
                    RefreshToken.builder()
                            .user(user)
                            .tokenHash(tokenHash)
                            .expiresAt(expiresAt)
                            .build();

            refreshTokenRepository.save(newToken);
        }
    }

    // 토큰 자체가 정상인지 검증하는 메서드
    private void validateRefreshToken(String refreshToken) {

        // refreshToken이 비어있는 경우
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(
                    AuthErrorCode.REFRESH_TOKEN_NOT_FOUND
            );
        }

        TokenStatus tokenStatus =
                tokenProvider.validateToken(
                        refreshToken,
                        TokenType.REFRESH
                );

        // 만료 되었는지 검증
        if (tokenStatus == TokenStatus.EXPIRED) {
            throw new BusinessException(
                    AuthErrorCode.EXPIRED_REFRESH_TOKEN
            );
        }

        // tokenStatus가 valid인지 검증
        if (tokenStatus != TokenStatus.VALID) {
            throw new BusinessException(
                    AuthErrorCode.INVALID_REFRESH_TOKEN
            );
        }
    }

    private RefreshToken getAndValidateStoredRefreshTokenForUpdate(
            Long userId,
            String rawRefreshToken
    ) {
        RefreshToken storedToken =
                refreshTokenRepository.findByUserIdForUpdate(userId)
                        .orElseThrow(() ->
                                new BusinessException(
                                        AuthErrorCode.REFRESH_TOKEN_NOT_FOUND
                                )
                        );

        LocalDateTime now = LocalDateTime.now();

        if (!storedToken.getExpiresAt().isAfter(now)) {
            throw new BusinessException(
                    AuthErrorCode.EXPIRED_REFRESH_TOKEN
            );
        }

        if (!refreshTokenHasher.matches(
                storedToken.getTokenHash(),
                rawRefreshToken
        )) {
            throw new BusinessException(
                    AuthErrorCode.REFRESH_TOKEN_MISMATCH
            );
        }

        return storedToken;
    }

    private void rotateRefreshToken(
            RefreshToken storedToken,
            String newRefreshToken
    ) {
        LocalDateTime now = LocalDateTime.now();

        String newTokenHash =
                refreshTokenHasher.hash(newRefreshToken);

        LocalDateTime expiresAt =
                now.plus(
                        jwtProperties.getRefreshTokenValidity()
                );

        storedToken.rotate(
                newTokenHash,
                expiresAt
        );

        refreshTokenRepository.saveAndFlush(storedToken);
    }
}