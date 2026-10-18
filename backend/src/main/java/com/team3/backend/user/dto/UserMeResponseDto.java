package com.team3.backend.user.dto;

import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;

public record UserMeResponseDto(
        Long id,
        String nickname,
        String email,
        Role role
) {
    public static UserMeResponseDto from(User user) {
        return new UserMeResponseDto(
                user.getId(),
                user.getNickname(),
                user.getEmail(),
                user.getRole()
        );
    }
}
