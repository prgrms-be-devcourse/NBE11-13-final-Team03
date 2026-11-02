package com.team3.dataservice.opportunity.normalization;

import com.team3.dataservice.opportunity.normalization.CategoryNormalizer.CategoryPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryNormalizerTest {

    @Test
    void 대분류와_중분류_개수가_같으면_같은_위치끼리_짝짓는다() {
        assertThat(CategoryNormalizer.split("일자리,교육", "취업,미래역량강화")).containsExactly(
                new CategoryPair("일자리", "취업"), new CategoryPair("교육", "미래역량강화"));
    }

    @Test
    void 같은_대분류가_반복되면_중분류마다_짝을_만들고_완전히_같은_짝은_한_번만_남긴다() {
        assertThat(CategoryNormalizer.split("일자리,일자리,일자리", "취업,창업,취업")).containsExactly(
                new CategoryPair("일자리", "취업"), new CategoryPair("일자리", "창업"));
    }

    @Test
    void 분류가_하나면_한_짝이고_공백과_빈_항목은_버린다() {
        assertThat(CategoryNormalizer.split(" 주거 ", "주거지원")).containsExactly(new CategoryPair("주거", "주거지원"));
        assertThat(CategoryNormalizer.split("교육, ,복지문화", "미래역량강화,,문화활동")).containsExactly(
                new CategoryPair("교육", "미래역량강화"), new CategoryPair("복지문화", "문화활동"));
    }

    @Test
    void 개수가_다르고_대분류가_하나뿐이면_모든_중분류와_짝짓는다() {
        assertThat(CategoryNormalizer.split("일자리", "취업,창업")).containsExactly(
                new CategoryPair("일자리", "취업"), new CategoryPair("일자리", "창업"));
        assertThat(CategoryNormalizer.split("일자리,일자리", "취업,창업,재직자")).containsExactly(
                new CategoryPair("일자리", "취업"), new CategoryPair("일자리", "창업"), new CategoryPair("일자리", "재직자"));
    }

    @Test
    void 개수가_다르고_대분류가_여럿이면_짝을_추정하지_않고_각각_따로_남긴다() {
        assertThat(CategoryNormalizer.split("일자리,교육", "취업,창업,미래역량강화")).containsExactly(
                new CategoryPair("일자리", null), new CategoryPair("교육", null),
                new CategoryPair(null, "취업"), new CategoryPair(null, "창업"), new CategoryPair(null, "미래역량강화"));
    }

    @Test
    void 한쪽만_있거나_둘_다_없으면_있는_값만_남긴다() {
        assertThat(CategoryNormalizer.split("일자리,교육", null)).containsExactly(
                new CategoryPair("일자리", null), new CategoryPair("교육", null));
        assertThat(CategoryNormalizer.split(null, "취업")).containsExactly(new CategoryPair(null, "취업"));
        assertThat(CategoryNormalizer.split(null, " ")).isEmpty();
        assertThat(CategoryNormalizer.split("", null)).isEmpty();
    }
}
