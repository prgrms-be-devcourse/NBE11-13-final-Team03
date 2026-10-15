package com.team3.backend.opportunity.normalization.official;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.Arrays;
import java.util.List;

/**
 * 원문을 줄 단위 텍스트로 바꾼다. 표의 한 행(신청기간 | 2026.10.01 ~ ...)은 한 줄이 되도록 셀은 공백으로 잇는다.
 */
public final class OfficialText {

    private OfficialText() {
    }

    public static List<String> lines(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String text = looksLikeHtml(content) ? htmlToText(content) : content;
        return Arrays.stream(text.split("\\R"))
                .map(line -> line.replace(' ', ' ').replaceAll("[\\t ]+", " ").trim())
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** 공백을 모두 지운 문자열 (정책명 일치 확인용) */
    public static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static boolean looksLikeHtml(String content) {
        return content.contains("<") && content.contains(">");
    }

    private static String htmlToText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, header, footer, nav").remove();
        doc.select("br, p, div, li, tr, h1, h2, h3, h4, h5, h6, dt, dd").before("\n");
        doc.select("td, th").before(" ");
        return doc.body() != null ? doc.body().wholeText() : doc.wholeText();
    }
}
