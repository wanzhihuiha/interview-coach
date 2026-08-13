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
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面试成长方案的查询与生成编排服务。
 *
 * <p>面试接口使用认证用户 ID 和面试 ID 调用本服务。本服务先按两者查询成长方案缓存；
 * 未命中已完成记录时，通过面试服务校验归属并取得报告和面试快照，再调用教练组件执行
 * 本地方案组装，最后把 Markdown、结构化 JSON 和生成状态写回数据库。</p>
 *
 * <p>整个入口使用默认事务传播。失败分支会尝试把当前实体改为 {@link GrowthPlanStatus#FAILED}
 * 并保存后重新抛出原异常，但该写入与主流程处在同一事务中，不保证异常回滚后仍能独立提交。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthPlanService {

    /** 读取按用户隔离的缓存记录，并持久化生成中、完成或失败状态。 */
    private final GrowthPlanRepository growthPlanRepository;

    /** 校验面试归属并提供报告及创建时岗位快照。 */
    private final InterviewService interviewService;

    /** 根据岗位类别和薄弱点在本地组装结构化方案与 Markdown。 */
    private final CoachAgent coachAgent;

    /** 在响应对象与实体中的结构化 JSON 文本之间转换。 */
    private final ObjectMapper objectMapper;

    /**
     * 获取或生成当前用户面试对应的成长方案。
     *
     * <p>已完成记录直接从结构化 JSON 恢复；不存在、生成中或失败记录都会重新进入生成流程。
     * 首次调用会创建记录，重试则复用已有记录。报告或面试不存在、越权、方案组装或序列化失败时，
     * 原异常会继续交给上层处理。</p>
     *
     * @param userId 认证上下文提供的用户 ID，用于面试和成长方案的资源归属校验
     * @param interviewId 要读取或生成成长方案的面试 ID
     * @return 已缓存或本次生成的成长方案响应
     */
    @Transactional
    public GrowthPlanResponse getOrCreatePlan(Long userId, Long interviewId) {
        // 先按面试和用户联合查询缓存，避免只凭可枚举的面试 ID 读取其他用户方案。
        GrowthPlan existing = growthPlanRepository.findByInterviewIdAndUserId(interviewId, userId).orElse(null);
        if (existing != null && existing.getStatus() == GrowthPlanStatus.COMPLETED) {
            // 已完成记录不再重新组装；坏 JSON 会在转换方法内降级为仅含 Markdown 的响应。
            return toResponse(existing);
        }

        GrowthPlan plan = existing != null ? existing : new GrowthPlan();
        plan.setUserId(userId);
        plan.setInterviewId(interviewId);
        plan.setStatus(GrowthPlanStatus.GENERATING);
        // 先保存 GENERATING，使新实体取得主键，并让后续响应和结构化 JSON 使用同一方案 ID。
        growthPlanRepository.save(plan);

        try {
            // 报告服务先校验面试归属；首次获取报告时还可能生成并持久化报告。
            InterviewReportResponse report = interviewService.getReport(userId, interviewId);
            // 将报告整理成文本是当前遗留输入准备步骤；三参数教练入口并不读取该文本。
            String reportText = buildReportText(report);
            // 读取同一用户的面试详情，以取得创建时保存的岗位名称和类别快照。
            InterviewDetailResponse interviewDetail = interviewService.getInterview(userId, interviewId);
            String positionTitle = interviewDetail.getPositionTitle();
            String jobCategory = normalizeJobCategory(interviewDetail.getJobCategory());
            // 教练组件当前只使用岗位类别和薄弱点按固定模板组装，不调用模型。
            GrowthPlanResponse response = coachAgent.generatePlan(reportText, jobCategory, report.getWeaknesses());

            plan.setPositionTitle(positionTitle);
            plan.setOverallScore(report.getOverallScore());
            plan.setGrade(report.getGrade());
            plan.setContent(response.getMdContent());
            response.setId(plan.getId());
            response.setPositionTitle(positionTitle);
            // 在回填方案 ID 和岗位标题后序列化，保证缓存命中时可恢复完整 API 响应。
            plan.setStructuredData(toJson(response));
            plan.setStatus(GrowthPlanStatus.COMPLETED);
            plan.setGeneratedAt(LocalDateTime.now());
            // 保存正文、结构化 JSON、报告摘要和 COMPLETED 状态；异常会进入同一事务的失败分支。
            growthPlanRepository.save(plan);

            return response;
        } catch (Exception e) {
            log.error("[GrowthPlanService] 生成成长方案失败: interviewId={}, userId={}", interviewId, userId, e);
            plan.setStatus(GrowthPlanStatus.FAILED);
            // 尝试记录失败状态后继续抛出原异常；默认事务回滚时该保存不保证最终留库。
            growthPlanRepository.save(plan);
            throw e;
        }
    }

    /**
     * 把报告摘要整理为旧单参数教练入口可解析的纯文本格式。
     *
     * <p>当前主流程仍构造此文本并传给三参数入口，但该入口不读取它；文本不被直接保存或返回。</p>
     */
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

    /**
     * 将完成态实体恢复为 API 响应；结构化 JSON 缺失或解析失败时降级返回主键、岗位标题和 Markdown。
     */
    private GrowthPlanResponse toResponse(GrowthPlan plan) {
        if (plan.getStructuredData() != null && !plan.getStructuredData().isBlank()) {
            try {
                // 优先恢复完整列表结构；坏 JSON 只影响结构化展示，不阻止读取已保存 Markdown。
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

    /**
     * 将完整响应序列化为持久化缓存；转换失败时中止生成并交由失败分支处理。
     */
    private String toJson(GrowthPlanResponse response) {
        try {
            // 保存 API 结构快照，使后续缓存命中无需再次执行教练组装。
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("成长方案序列化失败", e);
        }
    }

    /** 岗位类别为空时使用通用编码；其他值原样交给教练组件做进一步归一化。 */
    private String normalizeJobCategory(String jobCategory) {
        return jobCategory == null || jobCategory.isBlank() ? "GENERAL" : jobCategory;
    }
}
