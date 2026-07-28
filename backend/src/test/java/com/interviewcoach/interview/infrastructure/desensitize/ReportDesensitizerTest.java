package com.interviewcoach.interview.infrastructure.desensitize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.interviewcoach.interview.application.dto.InterviewReportResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 面试报告脱敏器单元测试。
 */
class ReportDesensitizerTest {

    private final ReportDesensitizer desensitizer = new ReportDesensitizer();

    @Test
    void shouldMaskCompanyNameInConclusionAndMarkdown() {
        InterviewReportResponse report = new InterviewReportResponse();
        report.setInterviewId(1L);
        report.setOverallScore(80);
        report.setGrade("良好");
        report.setStrengths(List.of("熟悉阿里巴巴公司的技术栈"));
        report.setWeaknesses(List.of("对阿里巴巴的缓存方案理解不深"));
        report.setConclusion("候选人在阿里巴巴公司面试中表现良好");
        report.setMdContent("# 报告\n候选人在阿里巴巴公司表现良好");

        InterviewReportResponse result = desensitizer.desensitize(report, "阿里巴巴", "张三");

        assertEquals("熟悉某公司公司的技术栈", result.getStrengths().get(0));
        assertEquals("对某公司的缓存方案理解不深", result.getWeaknesses().get(0));
        assertEquals("候选人在某公司公司面试中表现良好", result.getConclusion());
        assertFalse(result.getMdContent().contains("阿里巴巴"));
        assertTrue(result.getMdContent().contains("某公司"));
    }

    @Test
    void shouldMaskCandidateName() {
        InterviewReportResponse report = new InterviewReportResponse();
        report.setInterviewId(1L);
        report.setOverallScore(80);
        report.setGrade("良好");
        report.setStrengths(List.of("张三沟通能力较强"));
        report.setWeaknesses(List.of());
        report.setConclusion("张三整体表现良好");
        report.setMdContent("# 报告\n张三表现良好");

        InterviewReportResponse result = desensitizer.desensitize(report, "腾讯", "张三");

        assertEquals("候选人沟通能力较强", result.getStrengths().get(0));
        assertEquals("候选人整体表现良好", result.getConclusion());
        assertFalse(result.getMdContent().contains("张三"));
    }

    @Test
    void shouldKeepGenericCompanyTerms() {
        InterviewReportResponse report = new InterviewReportResponse();
        report.setInterviewId(1L);
        report.setStrengths(List.of("熟悉本公司业务"));

        InterviewReportResponse result = desensitizer.desensitize(report, "字节跳动", null);

        assertEquals("熟悉本公司业务", result.getStrengths().get(0));
    }
}
