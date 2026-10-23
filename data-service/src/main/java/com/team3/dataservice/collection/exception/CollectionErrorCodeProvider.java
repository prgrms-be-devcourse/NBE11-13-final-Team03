package com.team3.dataservice.collection.exception;

import com.team3.dataservice.global.exception.ErrorCode;
import com.team3.dataservice.global.exception.ErrorCodeProvider;
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
