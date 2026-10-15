package com.team3.backend.opportunity.normalization;

import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.eligibility.domain.entity.MajorField;
import com.team3.backend.eligibility.domain.entity.MaritalStatus;
import com.team3.backend.eligibility.domain.entity.SpecialTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 youth-policy-codes.yaml이 바인딩되는지, 내부 값이 프로필 enum과 맞는지 검사한다. */
class YouthPolicyCodeTableTest {

    private static YouthPolicyCodeTable table;

    @BeforeAll
    static void load() throws Exception {
        var sources = new YamlPropertySourceLoader().load("codes", new ClassPathResource("youth-policy-codes.yaml"));
        table = new Binder(ConfigurationPropertySources.from(sources))
                .bind("youth-policy.codes", YouthPolicyCodeTable.class).get();
    }

    @Test
    void 코드정보의_코드가_바인딩된다() {
        assertThat(table.value("mrgSttsCd", "0055001")).contains("MARRIED");
        assertThat(table.value("earnCndSeCd", "0043001")).contains("NO_LIMIT");
        assertThat(table.value("jobCd", "0013003")).contains("UNEMPLOYED");
        assertThat(table.value("schoolCd", "0049005")).contains("UNIVERSITY_ENROLLED");
        assertThat(table.value("sbizCd", "0014005")).contains("DISABLED");
        assertThat(table.value("sBizCd", "0014005")).contains("DISABLED");
    }

    @Test
    void 내부_값이_판정_프로필_enum과_맞는다() {
        assertValuesIn("mrgSttsCd", names(MaritalStatus.values()), "0055001", "0055002", "0055003");
        assertValuesIn("jobCd", names(EmploymentStatus.values()), codes("0013", 10));
        assertValuesIn("plcyMajorCd", names(MajorField.values()), codes("0011", 9));
        assertValuesIn("sBizCd", names(SpecialTarget.values()), codes("0014", 10));
    }

    private static void assertValuesIn(String field, Set<String> allowed, String... codes) {
        for (String code : codes) {
            String value = table.value(field, code).orElseThrow(() -> new AssertionError(field + " " + code + " 없음"));
            if (!value.equals(YouthPolicyCodeTable.NO_LIMIT) && !value.equals(YouthPolicyCodeTable.OTHER)) {
                assertThat(allowed).as(field + " " + code).contains(value);
            }
        }
    }

    private static String[] codes(String prefix, int count) {
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = prefix + String.format("%03d", i + 1);
        }
        return result;
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }
}
