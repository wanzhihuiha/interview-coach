package com.interviewcoach.growth.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.growth.application.dto.GrowthPlanResponse;
import com.interviewcoach.growth.domain.agent.CoachAgent;
import com.interviewcoach.growth.domain.entity.GrowthPlan;
import com.interviewcoach.growth.domain.entity.GrowthPlanStatus;
import com.interviewcoach.growth.domain.repository.GrowthPlanRepository;
import com.interviewcoach.interview.application.dto.InterviewDetailResponse;
import com.interviewcoach.interview.application.dto.InterviewReportResponse;
import com.interviewcoach.interview.application.service.InterviewService;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.repository.PositionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成长方案应用服务：基于面试报告生成并管理成长方案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthPlanService {

    private final GrowthPlanRepository growthPlanRepository;
    private final InterviewService interviewService;
    private final PositionRepository positionRepository;
    private final CoachAgent coachAgent;
    private final ObjectMapper objectMapper;

    /**
     * 获取或生成面试对应的成长方案。
     */
    @Transactional
    public GrowthPlanResponse getOrCreatePlan(Long userId, Long interviewId) {
        GrowthPlan existing = growthPlanRepository.findByInterviewIdAndUserId(interviewId, userId).orElse(null);
        if (existing != null && existing.getStatus() == GrowthPlanStatus.COMPLETED) {
            return toResponse(existing);
        }

        GrowthPlan plan = existing != null ? existing : new GrowthPlan();
        plan.setUserId(userId);
        plan.setInterviewId(interviewId);
        plan.setStatus(GrowthPlanStatus.GENERATING);
        growthPlanRepository.save(plan);

        try {
            InterviewReportResponse report = interviewService.getReport(userId, interviewId);
            String reportText = buildReportText(report);
            InterviewDetailResponse interviewDetail = interviewService.getInterview(userId, interviewId);
            String positionTitle = interviewDetail.getPositionTitle();
            String jobCategory = resolveJobCategory(interviewDetail.getPositionId());
            GrowthPlanResponse response = coachAgent.generatePlan(reportText, jobCategory, report.getWeaknesses());

            plan.setPositionTitle(positionTitle);
            plan.setOverallScore(report.getOverallScore());
            plan.setGrade(report.getGrade());
            plan.setContent(response.getMdContent());
            response.setId(plan.getId());
            response.setPositionTitle(positionTitle);
            plan.setStructuredData(toJson(response));
            plan.setStatus(GrowthPlanStatus.COMPLETED);
            plan.setGeneratedAt(LocalDateTime.now());
            growthPlanRepository.save(plan);

            return response;
        } catch (Exception e) {
            log.error("[GrowthPlanService] 生成成长方案失败: interviewId={}, userId={}", interviewId, userId, e);
            plan.setStatus(GrowthPlanStatus.FAILED);
            growthPlanRepository.save(plan);
            throw e;
        }
    }

    private String buildReportText(InterviewReportResponse report) {
        StringBuilder sb = new StringBuilder();
        sb.append("综合评分：").append(report.getOverallScore()).append("\n");
        sb.append("等级：").append(report.getGrade()).append("\n");
        sb.append("优势：").append(String.join("、", report.getStrengths())).append("\n");
        sb.append("劣势：").append(String.join("、", report.getWeaknesses())).append("\n");
        sb.append("各环节情况：\n");
        report.getPhases().forEach((phase, summary) ->
                sb.append("- ").append(phase).append("：")
                        .append(summary.getQuestionCount()).append("题，")
                        .append(Boolean.TRUE.equals(summary.getCompleted()) ? "已完成" : "未完成").append("\n"));
        InterviewReportResponse.DimensionScores dims = report.getDimensions();
        sb.append("维度评分：技术深度=").append(dims.getTechnicalDepth())
                .append("，技术广度=").append(dims.getTechnicalBreadth())
                .append("，实践经验=").append(dims.getPracticalExperience())
                .append("，表达能力=").append(dims.getExpression())
                .append("，学习能力=").append(dims.getLearningAbility()).append("\n");
        sb.append("结论：").append(report.getConclusion()).append("\n");
        return sb.toString();
    }

    private GrowthPlanResponse toResponse(GrowthPlan plan) {
        if (plan.getStructuredData() != null && !plan.getStructuredData().isBlank()) {
            try {
                return objectMapper.readValue(plan.getStructuredData(), GrowthPlanResponse.class);
            } catch (JsonProcessingException e) {
                log.warn("[GrowthPlanService] 结构化数据解析失败，降级返回 Markdown: {}", e.getMessage());
            }
        }
        GrowthPlanResponse response = new GrowthPlanResponse();
        response.setId(plan.getId());
        response.setPositionTitle(plan.getPositionTitle());
        response.setMdContent(plan.getContent());
        return response;
    }

    private String toJson(GrowthPlanResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("成长方案序列化失败", e);
        }
    }

    /**
     * 根据岗位 ID 解析岗位大类，用于 CoachAgent 生成差异化成长方案。
     */
    private String resolveJobCategory(Long positionId) {
        if (positionId == null) {
            return "GENERAL";
        }
        return positionRepository.findById(positionId)
                .map(Position::getJobCategory)
                .orElse("GENERAL");
    }
}
