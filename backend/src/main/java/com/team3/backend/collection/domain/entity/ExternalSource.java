package com.team3.backend.collection.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "external_source")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExternalSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_code", nullable = false, unique = true)
    private String sourceCode;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "base_url", nullable = false, columnDefinition = "text")
    private String baseUrl;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public ExternalSource(String sourceCode, String sourceName, String baseUrl,
                          boolean enabled, OffsetDateTime createdAt) {
        this.sourceCode = sourceCode;
        this.sourceName = sourceName;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }
}
