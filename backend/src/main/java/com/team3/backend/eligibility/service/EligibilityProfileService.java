package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.dto.EligibilityProfileResponse;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.region.domain.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

import static com.team3.backend.eligibility.exception.EligibilityErrorCode.INVALID_REGION_CODE;
import static com.team3.backend.eligibility.exception.EligibilityErrorCode.PROFILE_NOT_FOUND;

/**
 * GET·PUT /api/users/me/eligibility-profile.
 * PUT은 전체 교체이며, 프로필이 없으면 새로 만든다.
 * 거주지는 하위 지역이 없는 시·군·구 코드(세종 포함)여야 하고, 학교 소재지는 사전에 있는 코드면 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EligibilityProfileService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EligibilityProfileRepository profileRepository;
    private final RegionRepository regionRepository;
    private final RegionDirectory regionDirectory;
    private final EligibilityProfileValidator validator;

    public EligibilityProfileResponse get(Long userId) {
        return profileRepository.findByUserId(userId)
                .map(EligibilityProfileResponse::from)
                .orElseThrow(() -> new BusinessException(PROFILE_NOT_FOUND));
    }

    @Transactional
    public EligibilityProfileResponse put(Long userId, EligibilityProfileUpdateRequest request) {
        validator.validate(request, LocalDate.now(KST));

        String residenceCode = EligibilityProfileValidator.blankToNull(request.residenceRegionCode());
        if (!regionDirectory.index().children(residenceCode).isEmpty()) {
            throw new BusinessException(INVALID_REGION_CODE, "거주지는 시·군·구 코드여야 합니다: " + residenceCode);
        }
        Region residence = findRegion(residenceCode);
        String schoolCode = EligibilityProfileValidator.blankToNull(request.schoolRegionCode());
        Region school = schoolCode == null ? null : findRegion(schoolCode);

        EligibilityProfile source = request.toEntity(userId, residence, school);
        EligibilityProfile saved = profileRepository.findByUserId(userId)
                .map(existing -> {
                    existing.overwrite(source);
                    return existing;
                })
                .orElseGet(() -> profileRepository.save(source));
        // updatedAt(@PrePersist/@PreUpdate)이 응답에 반영되도록 즉시 flush
        profileRepository.flush();
        return EligibilityProfileResponse.from(saved);
    }

    private Region findRegion(String code) {
        return regionRepository.findById(code)
                .orElseThrow(() -> new BusinessException(INVALID_REGION_CODE, "지역 사전에 없는 코드입니다: " + code));
    }
}
