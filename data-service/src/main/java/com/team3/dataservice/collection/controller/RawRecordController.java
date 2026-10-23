package com.team3.dataservice.collection.controller;

import com.team3.dataservice.collection.dto.request.RawRecordSaveRequest;
import com.team3.dataservice.collection.dto.response.RawRecordResponse;
import com.team3.dataservice.collection.service.RawRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 원본 레코드 내부 API. 메인 서비스 관리자 API가 내부 키로 호출한다 (InternalApiKeyFilter) */
@RestController
@RequestMapping("/internal/collection/raw-records")
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
