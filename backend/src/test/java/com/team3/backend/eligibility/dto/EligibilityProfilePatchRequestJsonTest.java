package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.EmploymentStatus;
import com.team3.backend.user.mapper.MatchingConditionPatchMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class EligibilityProfilePatchRequestJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    private MatchingConditionPatchMapper patchMapper;

    @BeforeEach
    void setUp() {
        patchMapper = new MatchingConditionPatchMapper(objectMapper);
    }

    @Test
    void 요청에_없는_필드는_null로_변환한다() {
        EligibilityProfilePatchRequest request =
                patchMapper.from(json("""
                        {}
                        """));

        assertThat(request.schoolRegionCode()).isNull();
        assertThat(request.employmentStatus()).isNull();
    }

    @Test
    void 명시적인_null은_Optional_empty로_변환한다() {
        EligibilityProfilePatchRequest request =
                patchMapper.from(json("""
                        {
                          "schoolRegionCode": null
                        }
                        """));

        assertThat(request.schoolRegionCode())
                .isEqualTo(Optional.empty());
    }

    @Test
    void 전달한_값은_Optional에_담아_변환한다() {
        EligibilityProfilePatchRequest request =
                patchMapper.from(json("""
                        {
                          "employmentStatus": "EMPLOYED"
                        }
                        """));

        assertThat(request.employmentStatus())
                .contains(EmploymentStatus.EMPLOYED);
    }

    private ObjectNode json(String source) {
        return (ObjectNode) objectMapper.readTree(source);
    }
}