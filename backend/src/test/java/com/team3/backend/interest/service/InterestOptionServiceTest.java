package com.team3.backend.interest.service;

import com.team3.backend.interest.domain.repository.InterestOptionRepository;
import com.team3.backend.interest.dto.InterestCategoryOptionResponse;
import com.team3.backend.interest.dto.InterestOptionRow;
import com.team3.backend.interest.dto.InterestOptionsResponse;
import com.team3.backend.interest.dto.InterestSubCategoryOptionResponse;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.team3.backend.opportunity.domain.entity.OpportunityType.VOCATIONAL_TRAINING;
import static com.team3.backend.opportunity.domain.entity.OpportunityType.YOUTH_POLICY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterestOptionServiceTest {

    private static final List<OpportunityType> POLICY = List.of(YOUTH_POLICY);
    private static final List<OpportunityType> TRAINING = List.of(VOCATIONAL_TRAINING);
    private static final List<OpportunityType> BOTH = List.of(YOUTH_POLICY, VOCATIONAL_TRAINING);

    @Mock
    private InterestOptionRepository interestOptionRepository;

    @InjectMocks
    private InterestOptionService interestOptionService;

    @Test
    void 대분류_중분류_세분류를_계층으로_구성한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow(VOCATIONAL_TRAINING, "교육･직업훈련", "정보통신", "정보기술"),
                        new InterestOptionRow(YOUTH_POLICY, "일자리", "취업지원", null)
                ));

        InterestOptionsResponse response =
                interestOptionService.getOptions();

        assertEquals(
                new InterestOptionsResponse(List.of(
                        new InterestCategoryOptionResponse("교육･직업훈련",
                                List.of(new InterestSubCategoryOptionResponse("정보통신", List.of("정보기술"), TRAINING)),
                                TRAINING),
                        new InterestCategoryOptionResponse("일자리",
                                List.of(new InterestSubCategoryOptionResponse("취업지원", List.of(), POLICY)),
                                POLICY)
                )),
                response
        );
    }

    @Test
    void 청년정책과_직업훈련이_같은_대분류명을_쓰면_한_노드에_유형을_함께_담고_중분류는_유형별로_구분한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow(VOCATIONAL_TRAINING, "교육･직업훈련", "정보통신", "정보기술"),
                        new InterestOptionRow(YOUTH_POLICY, "교육･직업훈련", "미래역량강화", null),
                        new InterestOptionRow(YOUTH_POLICY, "교육･직업훈련", "온·오프라인교육", null),
                        // 두 유형에 같은 중분류명이 있으면 중분류도 두 유형을 담는다 (세분류는 직업훈련 것만 있다)
                        new InterestOptionRow(YOUTH_POLICY, "교육･직업훈련", "공통", null),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "교육･직업훈련", "공통", "공통세부"),
                        new InterestOptionRow(YOUTH_POLICY, "교육", "미래역량강화", null)
                ));

        InterestOptionsResponse response = interestOptionService.getOptions();

        assertEquals(
                new InterestOptionsResponse(List.of(
                        new InterestCategoryOptionResponse("교육",
                                List.of(new InterestSubCategoryOptionResponse("미래역량강화", List.of(), POLICY)),
                                POLICY),
                        new InterestCategoryOptionResponse("교육･직업훈련", List.of(
                                new InterestSubCategoryOptionResponse("공통", List.of("공통세부"), BOTH),
                                new InterestSubCategoryOptionResponse("미래역량강화", List.of(), POLICY),
                                new InterestSubCategoryOptionResponse("온·오프라인교육", List.of(), POLICY),
                                new InterestSubCategoryOptionResponse("정보통신", List.of("정보기술"), TRAINING)),
                                BOTH)
                )),
                response
        );
    }

    @Test
    void 중복을_제거하고_각_분류를_이름순으로_정렬한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow(VOCATIONAL_TRAINING, "나분류", "나중분류", "나세분류"),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "가분류", "나중분류", null),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "가분류", "가중분류", "나세분류"),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "가분류", "가중분류", "가세분류"),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "가분류", "가중분류", "가세분류")
                ));

        InterestOptionsResponse response =
                interestOptionService.getOptions();

        assertEquals(
                new InterestOptionsResponse(List.of(
                        new InterestCategoryOptionResponse("가분류", List.of(
                                new InterestSubCategoryOptionResponse("가중분류", List.of("가세분류", "나세분류"), TRAINING),
                                new InterestSubCategoryOptionResponse("나중분류", List.of(), TRAINING)),
                                TRAINING),
                        new InterestCategoryOptionResponse("나분류",
                                List.of(new InterestSubCategoryOptionResponse("나중분류", List.of("나세분류"), TRAINING)),
                                TRAINING)
                )),
                response
        );
    }

    @Test
    void 비어있는_분류값은_제외하되_유효한_상위분류는_유지한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow(VOCATIONAL_TRAINING, null, "중분류", "세분류"),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "", "중분류", "세분류"),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "   ", "중분류", "세분류"),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "가분류", null, "무시할세분류"),
                        new InterestOptionRow(VOCATIONAL_TRAINING, "가분류", "   ", "무시할세분류"),
                        new InterestOptionRow(YOUTH_POLICY, "나분류", "중분류", null),
                        new InterestOptionRow(YOUTH_POLICY, "나분류", "중분류", ""),
                        new InterestOptionRow(YOUTH_POLICY, "나분류", "중분류", "   ")
                ));

        InterestOptionsResponse response =
                interestOptionService.getOptions();

        assertEquals(
                new InterestOptionsResponse(List.of(
                        new InterestCategoryOptionResponse("가분류", List.of(), TRAINING),
                        new InterestCategoryOptionResponse("나분류",
                                List.of(new InterestSubCategoryOptionResponse("중분류", List.of(), POLICY)),
                                POLICY)
                )),
                response
        );
    }

    @Test
    void 분류_문자열의_특수문자와_원본값을_그대로_유지한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow(VOCATIONAL_TRAINING, "교육･직업훈련", "정보통신", "정보기술")
                ));

        InterestOptionsResponse response =
                interestOptionService.getOptions();

        assertEquals(
                "교육･직업훈련",
                response.categories().getFirst().category()
        );
        assertEquals(
                "정보통신",
                response.categories().getFirst()
                        .subCategories().getFirst().subCategory()
        );
    }

    @Test
    void 정규화된_분류가_없으면_빈_목록을_반환한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of());

        InterestOptionsResponse response =
                interestOptionService.getOptions();

        assertTrue(response.categories().isEmpty());
    }
}
