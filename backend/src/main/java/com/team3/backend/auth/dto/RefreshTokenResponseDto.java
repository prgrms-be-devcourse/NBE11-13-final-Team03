package com.team3.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RefreshTokenResponseDto {

    private boolean validated;
    private String accessToken;
    private String refreshToken;

}
