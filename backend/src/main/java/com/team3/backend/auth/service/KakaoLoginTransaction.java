package com.team3.backend.auth.service;

import com.team3.backend.auth.oauth2.*;
import com.team3.backend.auth.unlink.*;
import com.team3.backend.user.domain.entity.*;
import com.team3.backend.user.domain.repository.UserRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KakaoLoginTransaction {
    private final UserRepository users;
    private final KakaoIdentityGuard guard;
    private final KakaoUnlinkJobs jobs;
    public KakaoLoginTransaction(UserRepository users, KakaoIdentityGuard guard, KakaoUnlinkJobs jobs) {
        this.users = users; this.guard = guard; this.jobs = jobs;
    }
    @Transactional
    public User login(OAuth2UserInfo info, AuthProvider provider, boolean admin, Instant userInfoRequestedAt) {
        guard.lock(info.id());
        jobs.requireLoginAllowed(info.id(), userInfoRequestedAt);
        return users.findByKakaoIdAndProvider(info.id(), provider).map(existing -> {
            existing.updateProfile(info.name());
            if (admin && existing.getRole() != Role.ADMIN) existing.promoteToAdmin();
            return existing;
        }).orElseGet(() -> users.saveAndFlush(User.builder().kakaoId(info.id()).provider(provider)
                .nickname(info.name()).email(info.email()).role(admin ? Role.ADMIN : Role.USER).build()));
    }
}
