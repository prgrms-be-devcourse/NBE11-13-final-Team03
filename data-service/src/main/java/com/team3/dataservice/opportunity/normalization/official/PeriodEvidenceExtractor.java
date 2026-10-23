package com.team3.dataservice.opportunity.normalization.official;

import com.team3.dataservice.opportunity.domain.status.ApplicationPeriod;
import com.team3.dataservice.opportunity.normalization.ApplicationPeriodNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 원문 줄에서 신청기간을 찾는다.
 * "신청기간·접수기간·모집기간·신청일정" 문맥이 있는 줄만 보고,
 * 같은 줄에 사업·운영·교육·훈련·활동기간이 함께 있으면 어느 기간인지 애매하므로 제외한다.
 * 연도가 없는 날짜(10.1.~10.31.)는 임의로 연도를 붙이지 않는다.
 */
@Component
public class PeriodEvidenceExtractor {

    private static final Pattern CONTEXT = Pattern.compile("(신청|접수|모집)\\s*(기간|일정|일시)");
    private static final Pattern OTHER_PERIOD = Pattern.compile("(사업|운영|교육|훈련|활동|지원|수행|협약)\\s*기간");

    public List<ApplicationPeriod> extract(List<String> lines) {
        List<ApplicationPeriod> periods = new ArrayList<>();
        for (String line : lines) {
            if (!CONTEXT.matcher(line).find() || OTHER_PERIOD.matcher(line).find()) {
                continue;
            }
            periods.addAll(ApplicationPeriodNormalizer.parseRanges(line));
        }
        return periods;
    }
}
