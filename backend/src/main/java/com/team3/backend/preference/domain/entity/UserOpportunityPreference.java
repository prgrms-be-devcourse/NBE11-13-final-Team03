package com.team3.backend.preference.domain.entity;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/** 사용자와 공고 사이의 독립적인 저장·숨김 상태를 보관한다. */
@Entity
@Table(
        name = "user_opportunity_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_opportunity_preferences_user_opportunity",
                columnNames = {"user_id", "opportunity_id"}
        ),
        indexes = @Index(
                name = "idx_user_opportunity_preferences_saved_users",
                columnList = "opportunity_id, is_saved, user_id"
        )
)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserOpportunityPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opportunity_id", nullable = false)
    private Opportunity opportunity;

    @Builder.Default
    @Column(name = "is_saved", nullable = false)
    private boolean isSaved = false;

    @Builder.Default
    @Column(name = "is_hidden", nullable = false)
    private boolean isHidden = false;

    @Column(name = "saved_at")
    private LocalDateTime savedAt;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 저장 상태로 전환할 때만 저장·수정 시각을 갱신한다. */
    public void markSaved() {
        if (!isSaved) {
            LocalDateTime now = LocalDateTime.now();
            this.isSaved = true;
            this.savedAt = now;
            touchUpdatedAt(now);
        }
    }

    /** 저장 상태를 해제할 때만 저장 시각을 비운다. */
    public void clearSaved() {
        if (isSaved) {
            this.isSaved = false;
            this.savedAt = null;
            touchUpdatedAt(LocalDateTime.now());
        }
    }

    /** 숨김 상태로 전환할 때만 숨김·수정 시각을 갱신한다. */
    public void markHidden() {
        if (!isHidden) {
            LocalDateTime now = LocalDateTime.now();
            this.isHidden = true;
            this.hiddenAt = now;
            touchUpdatedAt(now);
        }
    }

    /** 숨김 상태를 해제할 때만 숨김 시각을 비운다. */
    public void clearHidden() {
        if (isHidden) {
            this.isHidden = false;
            this.hiddenAt = null;
            touchUpdatedAt(LocalDateTime.now());
        }
    }

    private void touchUpdatedAt(LocalDateTime now) {
        // 연속 상태 변경이 같은 시각에 발생해도 수정 시각을 단조 증가시킨다.
        this.updatedAt = updatedAt == null || now.isAfter(updatedAt)
                ? now : updatedAt.plusNanos(1_000);
    }

    /** 저장과 숨김이 모두 해제되어 삭제 대상인지 확인한다. */
    public boolean isInactive() {
        return !isSaved && !isHidden;
    }
}
