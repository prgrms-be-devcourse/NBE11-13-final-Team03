package com.team3.backend.region.service;

import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.region.dto.RegionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegionQueryServiceTest {

    @Mock
    private RegionDirectory regionDirectory;

    @InjectMocks
    private RegionQueryService service;

    @Test
    void 코드_오름차순으로_상위코드와_말단_여부를_함께_반환한다() {
        when(regionDirectory.index()).thenReturn(new RegionIndex(List.of(
                new RegionIndex.Entry("4111100000", "장안구", "4111000000"),
                new RegionIndex.Entry("3611000000", "세종특별자치시", null),
                new RegionIndex.Entry("4100000000", "경기도", null),
                new RegionIndex.Entry("1168000000", "강남구", "1100000000"),
                new RegionIndex.Entry("4111000000", "수원시", "4100000000"),
                new RegionIndex.Entry("1100000000", "서울특별시", null))));

        assertThat(service.getRegions().regions()).containsExactly(
                new RegionResponse("1100000000", "서울특별시", null, false),
                new RegionResponse("1168000000", "강남구", "1100000000", true),
                new RegionResponse("3611000000", "세종특별자치시", null, true),
                new RegionResponse("4100000000", "경기도", null, false),
                new RegionResponse("4111000000", "수원시", "4100000000", false),
                new RegionResponse("4111100000", "장안구", "4111000000", true));
    }

    @Test
    void 상위코드가_사전에_없으면_최상위로_반환한다() {
        when(regionDirectory.index()).thenReturn(new RegionIndex(List.of(
                new RegionIndex.Entry("4772000000", "군위군", "4700000000"))));

        assertThat(service.getRegions().regions())
                .containsExactly(new RegionResponse("4772000000", "군위군", null, true));
    }

    @Test
    void 지역_사전이_비어_있으면_빈_목록() {
        when(regionDirectory.index()).thenReturn(new RegionIndex(List.of()));

        assertThat(service.getRegions().regions()).isEmpty();
    }
}
