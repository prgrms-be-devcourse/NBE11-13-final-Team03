package com.team3.backend.region.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * StanReginCd 응답.
 * {"StanReginCd": [ {"head": [{"totalCount"}, {"numOfRows","pageNo","type"}, {"RESULT": {...}}]}, {"row": [...]} ]}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StanReginCdResponse(
        @JsonProperty("StanReginCd") List<Section> sections
) {

    public static final String SUCCESS_CODE = "INFO-0";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(List<Head> head, List<Row> row) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Head(
            Integer totalCount,
            String numOfRows,
            String pageNo,
            String type,
            @JsonProperty("RESULT") Result result
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String resultCode, String resultMsg) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            @JsonProperty("region_cd") String regionCd,
            @JsonProperty("sido_cd") String sidoCd,
            @JsonProperty("sgg_cd") String sggCd,
            @JsonProperty("umd_cd") String umdCd,
            @JsonProperty("ri_cd") String riCd,
            @JsonProperty("locatadd_nm") String locataddNm,
            @JsonProperty("locathigh_cd") String locathighCd,
            @JsonProperty("locallow_nm") String locallowNm
    ) {
    }

    public Integer totalCount() {
        return heads().stream().map(Head::totalCount).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public Result result() {
        return heads().stream().map(Head::result).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public List<Row> rows() {
        return sections == null ? List.of() : sections.stream()
                .map(Section::row).filter(Objects::nonNull).flatMap(List::stream).toList();
    }

    private List<Head> heads() {
        return sections == null ? List.of() : sections.stream()
                .map(Section::head).filter(Objects::nonNull).flatMap(List::stream).toList();
    }
}
