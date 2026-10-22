package com.team3.backend.interest.service;

import com.team3.backend.interest.domain.repository.InterestOptionRepository;
import com.team3.backend.interest.dto.InterestCategoryOptionResponse;
import com.team3.backend.interest.dto.InterestOptionRow;
import com.team3.backend.interest.dto.InterestOptionsResponse;
import com.team3.backend.interest.dto.InterestSubCategoryOptionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterestOptionServiceTest {

    @Mock
    private InterestOptionRepository interestOptionRepository;

    @InjectMocks
    private InterestOptionService interestOptionService;

    @Test
    void 대분류_중분류_세분류를_계층으로_구성한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow(
                                "교육･직업훈련",
                                "정보통신",
                                "정보기술"
                        ),
                        new InterestOptionRow(
                                "일자리",
                                "취업지원",
                                null
                        )
                ));

        InterestOptionsResponse response =
                interestOptionService.getOptions();

        assertEquals(
                new InterestOptionsResponse(List.of(
                        new InterestCategoryOptionResponse(
                                "교육･직업훈련",
                                List.of(
                                        new InterestSubCategoryOptionResponse(
                                                "정보통신",
                                                List.of("정보기술")
                                        )
                                )
                        ),
                        new InterestCategoryOptionResponse(
                                "일자리",
                                List.of(
                                        new InterestSubCategoryOptionResponse(
                                                "취업지원",
                                                List.of()
                                        )
                                )
                        )
                )),
                response
        );
    }

    @Test
    void 중복을_제거하고_각_분류를_이름순으로_정렬한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow("나분류", "나중분류", "나세분류"),
                        new InterestOptionRow("가분류", "나중분류", null),
                        new InterestOptionRow("가분류", "가중분류", "나세분류"),
                        new InterestOptionRow("가분류", "가중분류", "가세분류"),
                        new InterestOptionRow("가분류", "가중분류", "가세분류")
                ));

        InterestOptionsResponse response =
                interestOptionService.getOptions();

        assertEquals(
                new InterestOptionsResponse(List.of(
                        new InterestCategoryOptionResponse(
                                "가분류",
                                List.of(
                                        new InterestSubCategoryOptionResponse(
                                                "가중분류",
                                                List.of("가세분류", "나세분류")
                                        ),
                                        new InterestSubCategoryOptionResponse(
                                                "나중분류",
                                                List.of()
                                        )
                                )
                        ),
                        new InterestCategoryOptionResponse(
                                "나분류",
                                List.of(
                                        new InterestSubCategoryOptionResponse(
                                                "나중분류",
                                                List.of("나세분류")
                                        )
                                )
                        )
                )),
                response
        );
    }

    @Test
    void 비어있는_분류값은_제외하되_유효한_상위분류는_유지한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow(null, "중분류", "세분류"),
                        new InterestOptionRow("", "중분류", "세분류"),
                        new InterestOptionRow("   ", "중분류", "세분류"),
                        new InterestOptionRow("가분류", null, "무시할세분류"),
                        new InterestOptionRow("가분류", "   ", "무시할세분류"),
                        new InterestOptionRow("나분류", "중분류", null),
                        new InterestOptionRow("나분류", "중분류", ""),
                        new InterestOptionRow("나분류", "중분류", "   ")
                ));

        InterestOptionsResponse response =
                interestOptionService.getOptions();

        assertEquals(
                new InterestOptionsResponse(List.of(
                        new InterestCategoryOptionResponse(
                                "가분류",
                                List.of()
                        ),
                        new InterestCategoryOptionResponse(
                                "나분류",
                                List.of(
                                        new InterestSubCategoryOptionResponse(
                                                "중분류",
                                                List.of()
                                        )
                                )
                        )
                )),
                response
        );
    }

    @Test
    void 분류_문자열의_특수문자와_원본값을_그대로_유지한다() {
        when(interestOptionRepository.findDistinctNormalizedCategories())
                .thenReturn(List.of(
                        new InterestOptionRow(
                                "교육･직업훈련",
                                "정보통신",
                                "정보기술"
                        )
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