package com.team3.backend.client.grpc;

import com.team3.contracts.collection.v1.CollectionServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration(proxyBeanMethods = false)
public class CollectionGrpcClientConfig {
    @Bean
    CollectionServiceGrpc.CollectionServiceBlockingStub collectionStub(GrpcChannelFactory channels,
            @Value("${yon.data-service.internal-api-key}") String key) {
        return CollectionServiceGrpc.newBlockingStub(channels.createChannel("data-service"))
                .withInterceptors(new InternalKeyClientInterceptor(key));
    }
}
