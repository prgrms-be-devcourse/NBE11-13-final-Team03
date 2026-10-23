package com.team3.dataservice.region.service;

import com.team3.dataservice.region.client.RegionCodeApiClient;
import com.team3.dataservice.region.directory.RegionDirectory;
import com.team3.dataservice.region.domain.entity.Region;
import com.team3.dataservice.region.domain.repository.RegionRepository;
import com.team3.dataservice.region.dto.RegionSyncResult;
import com.team3.dataservice.region.dto.StanReginCdResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 법정동코드 API의 시·도, 시·군·구 코드를 regions 테이블과 비교해 저장한다.
 * - 새 코드: 추가, 이름·상위 지역이 바뀐 코드: 수정, 같으면 그대로 둔다.
 * - API에 없는 기존 코드는 삭제하지 않고 결과에 보고한다.
 * API 호출은 트랜잭션 밖에서, 비교·저장은 하나의 트랜잭션에서 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionSyncService {

    private static final String NO_PARENT = "0000000000";

    private final RegionCodeApiClient apiClient;
    private final RegionRepository regionRepository;
    private final TransactionTemplate transactionTemplate;
    private final RegionDirectory regionDirectory;

    public RegionSyncResult sync() {
        List<RegionCode> targets = selectSidoAndSigungu(apiClient.fetchAll());
        RegionSyncResult result = transactionTemplate.execute(status -> apply(targets));
        regionDirectory.refresh();
        log.info("지역 코드 동기화 완료: created={}, updated={}, unchanged={}, missing={}",
                result.created(), result.updated(), result.unchanged(), result.missingCodes().size());
        return result;
    }

    /**
     * 시·도(sgg_cd=000)와 시·군·구 행만 고른다. 읍·면·동, 리는 제외. 상위 코드가 먼저 오도록 코드순 정렬.
     * API는 일반구(예: 수원시 장안구 4111100000)의 상위를 도(4100000000)로 주므로,
     * 같은 앞자리의 시(4111000000)가 있으면 그 시를 상위로 바로잡는다. (시 단위 정책과 구 거주자 매칭용)
     * 시·군·구 행이 아닌 행(읍·면·동, 리)은 제외되므로 byCode에는 시·도와 시·군·구만 있다.
     */
    static List<RegionCode> selectSidoAndSigungu(List<StanReginCdResponse.Row> rows) {
        Map<String, StanReginCdResponse.Row> byCode = new TreeMap<>();
        for (StanReginCdResponse.Row row : rows) {
            String code = row.regionCd();
            if (code == null || code.length() != 10 || !"000".equals(row.umdCd()) || !"00".equals(row.riCd())) {
                continue;
            }
            byCode.putIfAbsent(code, row);
        }
        return byCode.entrySet().stream()
                .map(e -> new RegionCode(e.getKey(), nameOf(e.getValue()), parentOf(e.getKey(), e.getValue(), byCode)))
                .toList();
    }

    /**
     * 일반구 보정은 "구"로 끝나는 지역의 상위 후보가 "시"일 때만 적용한다.
     * (예: 증평군 4374500000은 영동군 4374000000과 앞자리가 같지만 하위 지역이 아니다)
     */
    private static String parentOf(String code, StanReginCdResponse.Row row, Map<String, StanReginCdResponse.Row> byCode) {
        String cityCode = code.substring(0, 4) + "000000";
        StanReginCdResponse.Row city = byCode.get(cityCode);
        if (code.charAt(4) != '0' && !cityCode.equals(code) && city != null
                && nameOf(row).endsWith("구") && nameOf(city).endsWith("시")) {
            return cityCode;
        }
        return parentCodeOf(code, row.locathighCd());
    }

    RegionSyncResult apply(List<RegionCode> targets) {
        Map<String, Region> regions = regionRepository.findAll().stream()
                .collect(Collectors.toMap(Region::getRegionCode, Function.identity(), (a, b) -> a, HashMap::new));
        Set<String> seen = new HashSet<>();
        int created = 0, updated = 0, unchanged = 0;

        for (RegionCode target : targets) {
            seen.add(target.code());
            Region parent = target.parentCode() == null ? null : regions.get(target.parentCode());
            if (target.parentCode() != null && parent == null) {
                log.warn("상위 지역을 찾을 수 없습니다: code={}, parent={}", target.code(), target.parentCode());
            }
            Region existing = regions.get(target.code());
            if (existing == null) {
                Region region = Region.builder()
                        .regionCode(target.code())
                        .regionName(target.name())
                        .parent(parent)
                        .build();
                regions.put(target.code(), regionRepository.save(region));
                created++;
            } else if (existing.update(target.name(), parent)) {
                updated++;
            } else {
                unchanged++;
            }
        }

        List<String> missing = regions.keySet().stream().filter(code -> !seen.contains(code)).sorted().toList();
        return new RegionSyncResult(created, updated, unchanged, missing);
    }

    private static String nameOf(StanReginCdResponse.Row row) {
        if (row.locallowNm() != null && !row.locallowNm().isBlank()) {
            return row.locallowNm().trim();
        }
        String[] parts = row.locataddNm() == null ? new String[0] : row.locataddNm().trim().split("\\s+");
        return parts.length == 0 ? row.regionCd() : parts[parts.length - 1];
    }

    private static String parentCodeOf(String code, String highCode) {
        if (highCode == null || highCode.isBlank() || NO_PARENT.equals(highCode) || highCode.equals(code)) {
            return null;
        }
        return highCode;
    }

    record RegionCode(String code, String name, String parentCode) {
    }
}
