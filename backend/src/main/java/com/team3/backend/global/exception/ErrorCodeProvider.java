package com.team3.backend.global.exception;

import java.util.List;

public interface ErrorCodeProvider<T> {
    String getDomain();
    List<ErrorCode> getErrorCodes();
}
