package com.team3.backend.collection.controller;

import com.team3.backend.collection.exception.CollectionExceptionHandler;
import com.team3.backend.collection.service.RawRecordService;
import com.team3.backend.global.exception.GlobalExceptionHandler;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CollectionErrorResponseTests {
    @Test
    void collectionExceptionsUseGlobalDomainErrorResponses() throws Exception {
        var service = mock(RawRecordService.class);
        var global = new GlobalExceptionHandler();
        var mvc = MockMvcBuilders.standaloneSetup(new RawRecordController(service))
                .setControllerAdvice(global, new CollectionExceptionHandler(global)).build();
        when(service.get(1L)).thenThrow(new EntityNotFoundException("private detail"));
        mvc.perform(get("/api/admin/collection/raw-records/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLLECTION_004"));
        doThrow(new DataAccessResourceFailureException("private database detail")).when(service).get(1L);
        mvc.perform(get("/api/admin/collection/raw-records/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COLLECTION_006"))
                .andExpect(jsonPath("$.message").value("원본 데이터 저장소 처리에 실패했습니다."));
    }
}
