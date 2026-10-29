package com.team3.dataservice.region.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;

/**
 * 행정구역 코드 사전 (시·도 / 시·군·구).
 * 행정표준코드(법정동코드) API의 region_cd 10자리를 변환 없이 저장한다. 예: 서울특별시 1100000000, 강남구 1168000000
 * 코드는 앞자리 0 보존과 식별자 취급을 위해 문자열로 저장한다.
 */
@Entity
@Table(name = "regions")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

    @Id
    @Column(name = "region_code", length = 10)
    private String regionCode;

    @Column(name = "region_name", nullable = false, length = 100)
    private String regionName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "parent_code",
            foreignKey = @ForeignKey(name = "fk_regions_parent")
    )
    private Region parent;

    /** 이름이나 상위 지역이 바뀌었으면 반영하고 true를 반환한다. */
    public boolean update(String regionName, Region parent) {
        boolean changed = !Objects.equals(this.regionName, regionName)
                || !Objects.equals(codeOf(this.parent), codeOf(parent));
        this.regionName = Objects.requireNonNull(regionName, "regionName");
        this.parent = parent;
        return changed;
    }

    private static String codeOf(Region region) {
        return region == null ? null : region.getRegionCode();
    }
}
