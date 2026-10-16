package com.team3.backend.preference.domain.entity;

import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_opportunity_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_opportunity_preferences_user_opportunity",
                columnNames = {"user_id", "opportunity_id"}
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

    public void markSaved() {
        if (!isSaved) {
            LocalDateTime now = LocalDateTime.now();
            this.isSaved = true;
            this.savedAt = now;
            touchUpdatedAt(now);
        }
    }

    public void clearSaved() {
        if (isSaved) {
            this.isSaved = false;
            this.savedAt = null;
            touchUpdatedAt(LocalDateTime.now());
        }
    }

    public void markHidden() {
        if (!isHidden) {
            LocalDateTime now = LocalDateTime.now();
            this.isHidden = true;
            this.hiddenAt = now;
            touchUpdatedAt(now);
        }
    }

    public void clearHidden() {
        if (isHidden) {
            this.isHidden = false;
            this.hiddenAt = null;
            touchUpdatedAt(LocalDateTime.now());
        }
    }

    private void touchUpdatedAt(LocalDateTime now) {
        this.updatedAt = updatedAt == null || now.isAfter(updatedAt)
                ? now : updatedAt.plusNanos(1_000);
    }

    public boolean isInactive() {
        return !isSaved && !isHidden;
    }
}
