package com.team3.backend.opportunity.region;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 실제 API를 호출하지 않고, 받은 샘플 응답 형식으로 파싱·페이지 처리·인증키 인코딩을 검증한다. */
class RegionCodeApiClientTest {

    private static final String PAGE_1 = """
            {"StanReginCd":[{"head":[{"totalCount":3},{"numOfRows":"2","pageNo":"1","type":"JSON"},
            {"RESULT":{"resultCode":"INFO-0","resultMsg":"NOMAL SERVICE"}}]},
            {"row":[
             {"region_cd":"2720000000","sido_cd":"27","sgg_cd":"200","umd_cd":"000","ri_cd":"00","locatjumin_cd":"2720000000",
              "locatjijuk_cd":"2720000000","locatadd_nm":"대구광역시\\n남구","locat_order":4,"locat_rm":"",
              "locathigh_cd":"2700000000","locallow_nm":"남구","adpt_de":""},
             {"region_cd":"2717010900","sido_cd":"27","sgg_cd":"170","umd_cd":"109","ri_cd":"00","locatjumin_cd":"2717010900",
              "locatjijuk_cd":"2717010900","locatadd_nm":"대구광역시\\n서구\\n원대동3가","locat_order":9,"locat_rm":"",
              "locathigh_cd":"2717000000","locallow_nm":"원대동3가","adpt_de":""}]}]}
            """;
    private static final String PAGE_2 = """
            {"StanReginCd":[{"head":[{"totalCount":3},{"numOfRows":"2","pageNo":"2","type":"JSON"},
            {"RESULT":{"resultCode":"INFO-0","resultMsg":"NOMAL SERVICE"}}]},
            {"row":[{"region_cd":"2700000000","sido_cd":"27","sgg_cd":"000","umd_cd":"000","ri_cd":"00",
              "locatadd_nm":"대구광역시","locathigh_cd":"0000000000","locallow_nm":"대구광역시"}]}]}
            """;

    private MockRestServiceServer server;
    private RegionCodeApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RegionCodeApiProperties properties = new RegionCodeApiProperties();
        properties.setServiceKey("ab+c/d==");
        properties.setPageSize(2);
        client = new RegionCodeApiClient(builder, properties);
    }

    @Test
    void 전체_페이지를_받아_행을_합친다() {
        server.expect(requestTo(containsString("pageNo=1")))
                .andRespond(withSuccess(PAGE_1, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("pageNo=2")))
                .andRespond(withSuccess(PAGE_2, MediaType.APPLICATION_JSON));

        List<StanReginCdResponse.Row> rows = client.fetchAll();

        assertThat(rows).extracting(StanReginCdResponse.Row::regionCd)
                .containsExactly("2720000000", "2717010900", "2700000000");
        assertThat(rows.get(0).locathighCd()).isEqualTo("2700000000");
        server.verify();
    }

    @Test
    void 인증키의_특수문자를_인코딩해서_보낸다() {
        server.expect(requestTo(containsString("ServiceKey=ab%2Bc%2Fd%3D%3D")))
                .andRespond(withSuccess(PAGE_2.replace("\"totalCount\":3", "\"totalCount\":1"), MediaType.APPLICATION_JSON));

        client.fetchAll();
        server.verify();
    }

    @Test
    void Content_Type이_text_html이어도_JSON으로_읽는다() {
        server.expect(requestTo(containsString("pageNo=1")))
                .andRespond(withSuccess(PAGE_2.replace("\"totalCount\":3", "\"totalCount\":1"),
                        MediaType.parseMediaType("text/html;charset=UTF-8")));

        assertThat(client.fetchAll()).extracting(StanReginCdResponse.Row::regionCd).containsExactly("2700000000");
    }

    @Test
    void 인코딩_키는_다시_인코딩하지_않는다() {
        RegionCodeApiProperties properties = new RegionCodeApiProperties();
        properties.setServiceKey("ab%2Bc%2Fd%3D%3D");
        RegionCodeApiClient encodedKeyClient = new RegionCodeApiClient(RestClient.builder(), properties);

        assertThat(encodedKeyClient.pageUri(1).toString())
                .contains("ServiceKey=ab%2Bc%2Fd%3D%3D&")
                .doesNotContain("%25");
    }

    @Test
    void 오류_코드가_오면_예외를_던진다() {
        server.expect(requestTo(containsString("pageNo=1")))
                .andRespond(withSuccess(PAGE_1.replace("INFO-0", "ERROR-300"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ERROR-300");
    }

    @Test
    void 인증키가_없으면_호출하지_않는다() {
        RegionCodeApiClient noKey = new RegionCodeApiClient(RestClient.builder(), new RegionCodeApiProperties());

        assertThatThrownBy(noKey::fetchAll).isInstanceOf(IllegalStateException.class);
    }
}
