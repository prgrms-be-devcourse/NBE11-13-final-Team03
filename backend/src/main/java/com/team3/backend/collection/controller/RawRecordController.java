package com.team3.backend.collection.controller;

import com.team3.backend.collection.dto.request.RawRecordSaveRequest;
import com.team3.backend.collection.dto.response.RawRecordResponse;
import com.team3.backend.collection.service.RawRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/collection/raw-records")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class RawRecordController {
    private final RawRecordService service;

    @PostMapping
    public RawRecordResponse save(@RequestBody RawRecordSaveRequest request) {
        return service.save(request);
    }

    @GetMapping("/{rawRecordId}")
    public RawRecordResponse get(@PathVariable Long rawRecordId) {
        return service.get(rawRecordId);
    }
}
