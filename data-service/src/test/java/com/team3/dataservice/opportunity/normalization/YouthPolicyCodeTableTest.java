package com.team3.dataservice.opportunity.normalization;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 youth-policy-codes.yaml이 바인딩되는지, 내부 값이 메인 서비스 판정 프로필 enum과 맞는지 검사한다.
 * 판정 프로필 enum은 메인 서비스(backend)에 있어 직접 참조할 수 없으므로 값 목록을 계약으로 적어 둔다.
 * 메인의 MaritalStatus·EmploymentStatus·MajorField·SpecialTarget이 바뀌면 이 목록도 같이 바꾼다.
 */
class YouthPolicyCodeTableTest {

    private static final Set<String> MARITAL_STATUS = Set.of("SINGLE", "MARRIED", "UNKNOWN");
    private static final Set<String> EMPLOYMENT_STATUS = Set.of("UNEMPLOYED", "EMPLOYED", "SELF_EMPLOYED", "FREELANCER",
            "PART_TIME", "STARTUP_PREPARING", "DAY_LABORER", "FARMER");
    private static final Set<String> MAJOR_FIELD = Set.of("HUMANITIES", "SOCIAL_SCIENCE", "BUSINESS_ECONOMICS",
            "NATURAL_SCIENCE", "ENGINEERING", "ARTS_AND_PHYSICAL", "AGRICULTURE", "OTHER");
    private static final Set<String> SPECIAL_TARGET = Set.of("SME_EMPLOYEE", "WOMAN", "BASIC_LIVELIHOOD_RECIPIENT",
            "SINGLE_PARENT", "DISABLED", "FARMER", "SOLDIER", "LOCAL_TALENT", "NEAR_POVERTY");

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
        assertValuesIn("mrgSttsCd", MARITAL_STATUS, "0055001", "0055002", "0055003");
        assertValuesIn("jobCd", EMPLOYMENT_STATUS, codes("0013", 10));
        assertValuesIn("plcyMajorCd", MAJOR_FIELD, codes("0011", 9));
        assertValuesIn("sBizCd", SPECIAL_TARGET, codes("0014", 10));
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
}
