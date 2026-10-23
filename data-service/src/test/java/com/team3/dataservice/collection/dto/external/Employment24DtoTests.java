package com.team3.dataservice.collection.dto.external;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class Employment24DtoTests {
    @Test
    void preservesRawFieldsIncludingUnknownNestedValues() {
        var page = JsonMapper.builder().build().readValue("""
                {
                  "srchList": [{
                    "trprId": "AIG20250000503483", "trprDegr": "8",
                    "title": "요양보호사자격취득과정", "subTitle": "고잔요양보호사교육원",
                    "ncsCd": "06010108", "instCd": "200801250",
                    "traStartDate": "2026-10-01", "traEndDate": "2027-01-31",
                    "trainTargetCd": "C0061", "trngAreaCd": "41273",
                    "yardMan": "40", "regCourseMan": "9",
                    "courseMan": "800000", "realMan": "800000",
                    "stdgScor": "88.9", "eiEmplRate3": "", "eiEmplRate6": "",
                    "certificate": "", "contents": "", "futureField": "unknown",
                    "unknownNested": {"values": [1, null, "원본"]}
                  }],
                  "scn_cnt": 138352, "pageSize": "20", "pageNum": "1"
                }
                """, Employment24Page.class);

        assertEquals(138352, page.totalCount());
        assertEquals("1", page.pageNum());
        assertEquals("20", page.pageSize());
        var course = page.srchList().getFirst();
        assertEquals("06010108", course.path("ncsCd").asString());
        assertEquals("고잔요양보호사교육원", course.path("subTitle").asString());
        assertEquals("2027-01-31", course.path("traEndDate").asString());
        assertEquals("800000", course.path("realMan").asString());
        assertEquals("88.9", course.path("stdgScor").asString());
        assertEquals("", course.path("eiEmplRate3").asString());
        assertTrue(course.path("telNo").isMissingNode());
        assertEquals("unknown", course.path("futureField").asString());
        var values = course.path("unknownNested").path("values");
        assertTrue(values.get(0).isIntegralNumber());
        assertEquals(1, values.get(0).asInt());
        assertTrue(values.get(1).isNull());
        assertEquals("원본", values.get(2).asString());
        assertThrows(UnsupportedOperationException.class, () -> page.srchList().clear());
    }

    @Test
    void distinguishesAnEmptyPageFromAMissingList() {
        var mapper = JsonMapper.builder().build();
        var page = mapper.readValue("""
                {"scn_cnt":0,"pageNum":"1","pageSize":"100","srchList":[]}
                """, Employment24Page.class);
        assertTrue(page.srchList().isEmpty());
        assertThrows(RuntimeException.class, () -> mapper.readValue("""
                {"scn_cnt":0,"pageNum":"1","pageSize":"100"}
                """, Employment24Page.class));
    }

    @Test
    void createsSixCalendarMonthsOfTrainingStartDates() {
        var request = Employment24SearchRequest.forNextSixMonths(
                "06", LocalDate.of(2026, 10, 15), 1, 100);
        assertEquals("06", request.srchNcs1());
        assertEquals(LocalDate.of(2026, 10, 15), request.srchTraStDt());
        assertEquals(LocalDate.of(2027, 4, 15), request.srchTraEndDt());
        var monthEnd = Employment24SearchRequest.forNextSixMonths(
                "24", LocalDate.of(2026, 8, 31), 1, 100);
        assertEquals(LocalDate.of(2027, 2, 28), monthEnd.srchTraEndDt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "00", "25", "06010108"})
    void rejectsInvalidNcsCategories(String code) {
        assertThrows(IllegalArgumentException.class, () ->
                Employment24SearchRequest.forNextSixMonths(code, LocalDate.of(2026, 10, 15), 1, 100));
    }

    @Test
    void rejectsInvalidDatesAndPaging() {
        var today = LocalDate.of(2026, 10, 15);
        assertThrows(IllegalArgumentException.class, () ->
                new Employment24SearchRequest("01", today, today.minusDays(1), 1, 100));
        assertThrows(IllegalArgumentException.class, () ->
                Employment24SearchRequest.forNextSixMonths("01", today, 0, 100));
        assertThrows(IllegalArgumentException.class, () ->
                Employment24SearchRequest.forNextSixMonths("01", today, 1001, 100));
        assertThrows(IllegalArgumentException.class, () ->
                Employment24SearchRequest.forNextSixMonths("01", today, 1, 101));
    }
}
