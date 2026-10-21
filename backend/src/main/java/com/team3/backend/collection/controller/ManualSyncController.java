package com.team3.backend.collection.controller;

import com.team3.backend.collection.domain.CollectionSource;
import com.team3.backend.collection.dto.response.SyncJobResponse;
import com.team3.backend.collection.dto.response.SyncStartResponse;
import com.team3.backend.collection.service.ManualSyncService;
import com.team3.backend.collection.service.SyncJobService;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/collection/syncs")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
public class ManualSyncController {
    private final ManualSyncService manualSync;
    private final SyncJobService jobs;

    @PostMapping("/{source}")
    public ResponseEntity<SyncStartResponse> start(@PathVariable CollectionSource source,
            @RequestParam(defaultValue = "100") int pageSize,
            @RequestParam(defaultValue = "1000") int maxPages) {
        var response = manualSync.start(source, pageSize, maxPages);
        return ResponseEntity.accepted().location(URI.create("/api/admin/collection/syncs/" + response.jobId()))
                .body(response);
    }

    @GetMapping
    @Operation(summary = "날짜별 수집 이력 조회",
            description = "한국 시간 기준 접수일(startedAt)로 모든 상태의 작업을 최신순 조회합니다. 날짜 형식은 yyyy-MM-dd입니다.")
    public List<SyncJobResponse> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return jobs.listByDate(date);
    }

    @GetMapping("/{jobId}")
    public SyncJobResponse get(@PathVariable Long jobId) {
        return jobs.get(jobId);
    }
}
