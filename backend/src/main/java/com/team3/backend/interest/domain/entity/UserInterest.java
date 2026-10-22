package com.team3.backend.interest.domain.entity;

import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "user_interests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_interests_user_type_code",
                columnNames = {"user_id", "interest_type", "interest_code"}
        ),
        indexes = @Index(
                name = "idx_user_interests_target_users",
                columnList = "interest_type, interest_code, user_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserInterest {

    private static final int MAX_INTEREST_CODE_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_interests_user")
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_type", nullable = false, length = 30)
    private InterestType interestType;

    @Column(name = "interest_code", nullable = false, length = 100)
    private String interestCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private UserInterest(
            User user,
            InterestType interestType,
            String interestCode
    ) {
        this.user = Objects.requireNonNull(user, "user");
        this.interestType = Objects.requireNonNull(interestType, "interestType");
        this.interestCode = normalizeCode(interestCode);
    }

    public static UserInterest create(
            User user,
            InterestType interestType,
            String interestCode
    ) {
        return new UserInterest(user, interestType, interestCode);
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    private static String normalizeCode(String interestCode) {
        Objects.requireNonNull(interestCode, "interestCode");

        String normalized = interestCode.strip();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "관심 분야 코드는 비어 있을 수 없습니다."
            );
        }

        if (normalized.length() > MAX_INTEREST_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "관심 분야 코드는 100자를 초과할 수 없습니다."
            );
        }

        return normalized;
    }
}