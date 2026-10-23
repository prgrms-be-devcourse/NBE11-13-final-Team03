package com.team3.dataservice.global.exception;

import java.util.List;

public interface ErrorCodeProvider<T> {
    String getDomain();
    List<ErrorCode> getErrorCodes();
}
