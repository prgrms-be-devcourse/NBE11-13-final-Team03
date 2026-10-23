package com.team3.dataservice.region.service;

import com.team3.dataservice.region.client.RegionCodeApiClient;
import com.team3.dataservice.region.directory.RegionDirectory;
import com.team3.dataservice.region.domain.entity.Region;
import com.team3.dataservice.region.domain.repository.RegionRepository;
import com.team3.dataservice.region.dto.RegionSyncResult;
import com.team3.dataservice.region.dto.StanReginCdResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DB 없이 비교·저장 로직을 검증한다. */
class RegionSyncServiceTest {

    private final RegionRepository repository = mock(RegionRepository.class);
    private final RegionSyncService service = new RegionSyncService(mock(RegionCodeApiClient.class), repository, null, mock(RegionDirectory.class));

    @Test
    void 시도와_시군구만_고르고_상위코드가_먼저_오도록_정렬한다() {
        List<RegionSyncService.RegionCode> selected = RegionSyncService.selectSidoAndSigungu(List.of(
                row("2717010900", "109", "2717000000", "원대동3가"),   // 읍면동 → 제외
                row("2720000000", "000", "2700000000", "남구"),
                row("2700000000", "000", "0000000000", "대구광역시")
        ));

        assertThat(selected).containsExactly(
                new RegionSyncService.RegionCode("2700000000", "대구광역시", null),
                new RegionSyncService.RegionCode("2720000000", "남구", "2700000000"));
    }

    @Test
    void 일반구의_상위는_도가_아니라_시로_바로잡는다() {
        List<RegionSyncService.RegionCode> selected = RegionSyncService.selectSidoAndSigungu(List.of(
                row("4100000000", "000", "0000000000", "경기도"),
                row("4111000000", "000", "4100000000", "수원시"),
                row("4111100000", "000", "4100000000", "장안구"),   // API는 상위를 경기도로 준다
                row("4113000000", "000", "4100000000", "성남시")
        ));

        assertThat(selected).containsExactly(
                new RegionSyncService.RegionCode("4100000000", "경기도", null),
                new RegionSyncService.RegionCode("4111000000", "수원시", "4100000000"),
                new RegionSyncService.RegionCode("4111100000", "장안구", "4111000000"),
                new RegionSyncService.RegionCode("4113000000", "성남시", "4100000000"));
    }

    @Test
    void 군은_앞자리가_같은_다른_군의_하위로_보정하지_않는다() {
        List<RegionSyncService.RegionCode> selected = RegionSyncService.selectSidoAndSigungu(List.of(
                row("4300000000", "000", "0000000000", "충청북도"),
                row("4374000000", "000", "4300000000", "영동군"),
                row("4374500000", "000", "4300000000", "증평군")
        ));

        assertThat(selected).contains(new RegionSyncService.RegionCode("4374500000", "증평군", "4300000000"));
    }

    @Test
    void 새_코드는_추가하고_바뀐_코드는_수정하며_없어진_코드는_보고만_한다() {
        Region daegu = Region.builder().regionCode("2700000000").regionName("대구광역시").build();
        Region oldName = Region.builder().regionCode("2720000000").regionName("옛 이름").parent(daegu).build();
        Region gone = Region.builder().regionCode("9900000000").regionName("폐지").build();
        when(repository.findAll()).thenReturn(List.of(daegu, oldName, gone));
        when(repository.save(any(Region.class))).thenAnswer(inv -> inv.getArgument(0));

        RegionSyncResult result = service.apply(List.of(
                new RegionSyncService.RegionCode("2700000000", "대구광역시", null),
                new RegionSyncService.RegionCode("2720000000", "남구", "2700000000"),
                new RegionSyncService.RegionCode("2717000000", "서구", "2700000000")));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.missingCodes()).containsExactly("9900000000");
        assertThat(oldName.getRegionName()).isEqualTo("남구");
        verify(repository, never()).delete(any());
    }

    private StanReginCdResponse.Row row(String code, String umd, String high, String name) {
        return new StanReginCdResponse.Row(code, code.substring(0, 2), code.substring(2, 5), umd, "00", name, high, name);
    }
}
