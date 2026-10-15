package com.team3.backend.auth.jwt;

import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenProvider {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TOKEN_TYPE = "token_type";
    private static final String CLAIM_SESSION_ID = "session_id";

    private final JwtProperties jwtProperties;

    private SecretKey secretKey;
    private JwtParser jwtParser;

    @PostConstruct
    private void init() {
        this.secretKey = Keys.hmacShaKeyFor(
                Base64.getDecoder()
                        .decode(jwtProperties.getSecretKey())
        );

        this.jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .build();
    }

    public Authentication getAuthentication(String token) {

        Claims claims = getClaims(token);

        Long userId =
                Long.valueOf(claims.getSubject());

        Role role =
                Role.valueOf(
                        claims.get(CLAIM_ROLE, String.class)
                );

        String sessionId =
                claims.get(CLAIM_SESSION_ID, String.class);

        CustomUserDetails principal =
                new CustomUserDetails(
                        userId,
                        role,
                        sessionId
                );

        return new UsernamePasswordAuthenticationToken(
                principal,
                token,
                principal.getAuthorities()
        );
    }

    public String generateToken(
            User user,
            String sessionId,
            Duration validity,
            TokenType tokenType
    ) {
        Date now = new Date();
        Date expiration =
                new Date(now.getTime() + validity.toMillis());

        return Jwts.builder()
                .header()
                .type("JWT")
                .and()
                .id(UUID.randomUUID().toString())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiration(expiration)
                .subject(String.valueOf(user.getId()))
                .claim(
                        CLAIM_ROLE,
                        user.getRole().name()
                )
                .claim(
                        CLAIM_TOKEN_TYPE,
                        tokenType.name()
                )
                .claim(
                        CLAIM_SESSION_ID,
                        sessionId
                )
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }

    public TokenType getTokenType(String token) {
        Claims claims = getClaims(token);

        return TokenType.valueOf(
                claims.get(CLAIM_TOKEN_TYPE, String.class)
        );
    }

    public TokenStatus validateToken(
            String token,
            TokenType expectedType
    ) {
        try {
            Claims claims = jwtParser
                    .parseSignedClaims(token)
                    .getPayload();

            String issuer = claims.getIssuer();
            String tokenType =
                    claims.get(CLAIM_TOKEN_TYPE, String.class);
            String sessionId =
                    claims.get(CLAIM_SESSION_ID, String.class);
            String subject = claims.getSubject();

            if (!jwtProperties.getIssuer().equals(issuer)) {
                return TokenStatus.INVALID;
            }

            if (!expectedType.name().equals(tokenType)) {
                return TokenStatus.INVALID;
            }

            if (sessionId == null || sessionId.isBlank()) {
                return TokenStatus.INVALID;
            }

            if (subject == null || subject.isBlank()) {
                return TokenStatus.INVALID;
            }

            Long.valueOf(subject);

            return TokenStatus.VALID;

        } catch (ExpiredJwtException e) {
            return TokenStatus.EXPIRED;
        } catch (Exception e) {
            return TokenStatus.INVALID;
        }
    }

    public String getSessionId(String token) {
        Claims claims = getClaims(token);

        return claims.get(
                CLAIM_SESSION_ID,
                String.class
        );
    }

    public Long getUserId(String token) {
        Claims claims = getClaims(token);

        return Long.valueOf(claims.getSubject());
    }

    public Role getRole(String token) {
        Claims claims = getClaims(token);

        return Role.valueOf(
                claims.get(CLAIM_ROLE, String.class)
        );
    }

    private Claims getClaims(String token) {
        return jwtParser
                .parseSignedClaims(token)
                .getPayload();
    }
}
