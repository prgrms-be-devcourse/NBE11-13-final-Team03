package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.dto.ConditionRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OpportunityConditionRepository extends JpaRepository<OpportunityCondition, Long> {

    List<OpportunityCondition> findByVersionId(Long versionId);

    List<OpportunityCondition> findByVersionIdIn(Collection<Long> versionIds);

    /** 판정용: 엔진이 읽는 컬럼만 */
    @Query("select new com.team3.backend.opportunity.dto.ConditionRow("
            + "c.version.id, c.conditionType, c.requirementType, c.conditionField, c.expression, c.validationStatus) "
            + "from OpportunityCondition c where c.version.id in :versionIds")
    List<ConditionRow> findRowsByVersionIdIn(@Param("versionIds") Collection<Long> versionIds);
}
