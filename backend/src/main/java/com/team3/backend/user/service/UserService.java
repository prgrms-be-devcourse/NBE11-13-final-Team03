package com.team3.backend.user.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.dto.UserMeResponseDto;
import com.team3.backend.user.exception.UserErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;


    @Operation(
            summary = "내 정보 조회",
            description = """
                    현재 로그인한 사용자의 정보를 조회합니다.
                    
                    Access Token을 통해 인증된 사용자의 정보를 반환합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "내 정보 조회 성공"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Access Token이 없거나 유효하지 않은 경우"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자 정보를 찾을 수 없는 경우"
            )
    })
    @SecurityRequirement(name = "cookieAuth")
    @Transactional(readOnly = true)
    public UserMeResponseDto getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        return UserMeResponseDto.from(user);
    }
}
