package com.team3.dataservice.opportunity.normalization;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class NcsClassificationTest {

    @Test
    void 대분류는_코드_앞_2자리로_찾는다() {
        assertThat(NcsClassification.majorName("20010202")).hasValue("정보통신");
        assertThat(NcsClassification.majorName("021001")).hasValue("경영·회계·사무");
        assertThat(NcsClassification.majorName(" 14070106 ")).hasValue("건설");
    }

    @Test
    void 대분류_24개가_모두_있다() {
        IntStream.rangeClosed(1, 24).mapToObj(i -> String.format("%02d010101", i))
                .forEach(code -> assertThat(NcsClassification.majorName(code)).as(code).isPresent());
    }

    @Test
    void 중분류는_코드_앞_4자리로_찾는다() {
        assertThat(NcsClassification.middleName("20010202")).hasValue("정보기술");
        assertThat(NcsClassification.middleName("14070106")).hasValue("건설기계운전·정비");
        assertThat(NcsClassification.middleName("140302")).hasValue("건축");
    }

    @Test
    void 중분류는_모두_대분류_아래에_있다() {
        var names = NcsClassification.load();
        assertThat(names.keySet()).allMatch(code -> code.length() == 2 || code.length() == 4);
        assertThat(names.keySet()).filteredOn(code -> code.length() == 4)
                .isNotEmpty()
                .allMatch(code -> names.containsKey(code.substring(0, 2)));
    }

    @Test
    void 코드가_없거나_짧거나_표에_없으면_빈_값() {
        assertThat(NcsClassification.majorName(null)).isEmpty();
        assertThat(NcsClassification.majorName("2")).isEmpty();
        assertThat(NcsClassification.majorName("99010101")).isEmpty();
        assertThat(NcsClassification.middleName("200")).isEmpty();
        assertThat(NcsClassification.middleName("20990101")).isEmpty();
    }

    /** 추천(backend RecommendationInterestMatcher)은 관심 분야를 중분류 이름으로 맞추므로, 같은 이름이 다른 대분류에 있으면 안 된다 */
    @Test
    void 중분류_이름은_대분류가_달라도_겹치지_않는다() {
        Map<String, Set<String>> majorsByMiddleName = new HashMap<>();
        NcsClassification.load().forEach((code, name) -> {
            if (code.length() == 4) {
                majorsByMiddleName.computeIfAbsent(name, ignored -> new HashSet<>()).add(code.substring(0, 2));
            }
        });
        assertThat(majorsByMiddleName).isNotEmpty();
        assertThat(majorsByMiddleName).allSatisfy((name, majors) -> assertThat(majors).as(name).hasSize(1));
    }
}
