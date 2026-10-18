package com.team3.backend.auth.jwt;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class RefreshTokenHasher {
    public String hash(String refreshToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            byte[] hash = md.digest(refreshToken.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 알고리즘을 사용할 수 없습니다.",
                    e
            );
        }
    }

    public boolean matches(
            String storedHash,
            String rawToken
    ) {
        String requestHash = hash(rawToken);

        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}
