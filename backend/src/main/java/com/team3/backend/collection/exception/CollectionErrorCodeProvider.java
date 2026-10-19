package com.team3.backend.collection.exception;

import com.team3.backend.global.exception.ErrorCode;
import com.team3.backend.global.exception.ErrorCodeProvider;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CollectionErrorCodeProvider implements ErrorCodeProvider<CollectionErrorCode> {
    @Override
    public String getDomain() {
        return "COLLECTION";
    }

    @Override
    public List<ErrorCode> getErrorCodes() {
        return List.of(CollectionErrorCode.values());
    }
}
