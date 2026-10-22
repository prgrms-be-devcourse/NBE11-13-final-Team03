package com.team3.backend.preference.exception;

import com.team3.backend.global.exception.ErrorCode;
import com.team3.backend.global.exception.ErrorCodeProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** 저장·숨김 오류 코드를 공통 오류 목록에 제공한다. */
@Component
public class PreferenceErrorCodeProvider implements ErrorCodeProvider<PreferenceErrorCode> {

    @Override
    public String getDomain() {
        return "PREFERENCE";
    }

    @Override
    public List<ErrorCode> getErrorCodes() {
        return List.of(PreferenceErrorCode.values());
    }
}
