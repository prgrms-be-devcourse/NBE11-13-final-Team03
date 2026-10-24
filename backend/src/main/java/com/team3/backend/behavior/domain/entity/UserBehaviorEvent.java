package com.team3.backend.behavior.domain.entity;

import com.team3.backend.opportunity.domain.entity.Opportunity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/** 인증 사용자의 공고 행동을 서버 수신 시각과 함께 보존한다. */
@Entity
@Table(name = "user_behavior_events", indexes = {
        @Index(name = "idx_user_behavior_events_user_opportunity", columnList = "user_id, opportunity_id"),
        @Index(name = "idx_user_behavior_events_opportunity", columnList = "opportunity_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBehaviorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_behavior_events_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opportunity_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_behavior_events_opportunity"))
    private Opportunity opportunity;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private UserBehaviorEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private UserBehaviorSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", length = 30)
    private ExternalLinkType linkType;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    public UserBehaviorEvent(User user, Opportunity opportunity, UserBehaviorEventType eventType,
                             UserBehaviorSource source, ExternalLinkType linkType) {
        this.user = user;
        this.opportunity = opportunity;
        this.eventType = eventType;
        this.source = source;
        this.linkType = linkType;
    }

    @PrePersist
    protected void onCreate() {
        receivedAt = LocalDateTime.now();
    }
}
