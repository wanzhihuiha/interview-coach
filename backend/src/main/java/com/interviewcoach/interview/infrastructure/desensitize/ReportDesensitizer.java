package com.interviewcoach.interview.infrastructure.desensitize;

import com.interviewcoach.interview.application.dto.InterviewReportResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 面试报告脱敏器。
 *
 * <p>对报告中可能包含的敏感信息进行脱敏处理，包括公司名称、候选人姓名等，
 * 确保报告内容可被安全查看和下载。</p>
 */
@Component
public class ReportDesensitizer {

    private static final String MASKED_COMPANY = "某公司";
    private static final String MASKED_NAME = "候选人";

    /**
     * 对报告内容进行脱敏。
     *
     * @param report      原始报告
     * @param companyName 公司名称（可为空）
     * @param candidateName 候选人姓名（可为空）
     * @return 脱敏后的报告
     */
    public InterviewReportResponse desensitize(InterviewReportResponse report,
                                               String companyName,
                                               String candidateName) {
        InterviewReportResponse result = new InterviewReportResponse();
        result.setInterviewId(report.getInterviewId());
        result.setOverallScore(report.getOverallScore());
        result.setGrade(report.getGrade());
        result.setPhases(report.getPhases());
        result.setDimensions(report.getDimensions());
        result.setKeyEvents(report.getKeyEvents());

        result.setStrengths(maskList(report.getStrengths(), companyName, candidateName));
        result.setWeaknesses(maskList(report.getWeaknesses(), companyName, candidateName));
        result.setConclusion(maskText(report.getConclusion(), companyName, candidateName));
        result.setMdContent(maskText(report.getMdContent(), companyName, candidateName));

        return result;
    }

    private List<String> maskList(List<String> items, String companyName, String candidateName) {
        if (items == null) {
            return null;
        }
        List<String> result = new ArrayList<>(items.size());
        for (String item : items) {
            result.add(maskText(item, companyName, candidateName));
        }
        return result;
    }

    private String maskText(String text, String companyName, String candidateName) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;

        // 精确替换公司名称
        if (companyName != null && !companyName.isBlank()) {
            result = result.replace(companyName, MASKED_COMPANY);
        }

        // 替换候选人姓名
        if (candidateName != null && !candidateName.isBlank()) {
            result = result.replace(candidateName, MASKED_NAME);
        }

        return result;
    }
}
