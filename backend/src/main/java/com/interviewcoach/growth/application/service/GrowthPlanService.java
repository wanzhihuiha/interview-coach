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
 * 成长方案应用服务。
 *
 * <p>上游由面试接口在用户查看成长方案时调用；本服务负责复用已有结果，或串联面试报告、
 * 岗位信息和 {@link CoachAgent} 生成并保存新方案，最终向接口层返回结构化内容。</p>
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
     * 获取或重新生成当前用户指定面试的成长方案。
     *
     * <p>仅直接复用 {@link GrowthPlanStatus#COMPLETED} 的记录；没有记录，或已有记录仍处于
     * 生成中/失败状态时，会先标记为生成中，再取得面试报告和岗位信息，调用 {@link CoachAgent}
     * 按本地规则组装内容，最后保存为完成状态。复用已完成记录时按方案记录中的用户 ID 限制访问；
     * 重新生成时，还会通过查询报告和面试详情校验面试归属。</p>
     *
     * <p>当前不要求面试必须结束，也没有加锁，并发请求可能同时生成同一面试的方案。
     * 报告查询或首次生成会加入本方法的事务；后续方案生成、序列化或保存失败并继续抛出异常时，
     * 本次写入的 {@code GENERATING}/{@code FAILED} 状态和新生成的报告都会一起回滚。</p>
     */
    @Transactional
    public GrowthPlanResponse getOrCreatePlan(Long userId, Long interviewId) {
        GrowthPlan existing = growthPlanRepository.findByInterviewIdAndUserId(interviewId, userId).orElse(null);
        if (existing != null && existing.getStatus() == GrowthPlanStatus.COMPLETED) {
            return toResponse(existing);
        }

        // 复用未完成的记录继续生成；没有记录时才创建新的方案载体。
        GrowthPlan plan = existing != null ? existing : new GrowthPlan();
        plan.setUserId(userId);
        plan.setInterviewId(interviewId);
        plan.setStatus(GrowthPlanStatus.GENERATING);
        growthPlanRepository.save(plan);

        try {
            // 1. 取得已有报告；报告不存在时，getReport 会在当前事务中同步生成并暂存报告。
            InterviewReportResponse report = interviewService.getReport(userId, interviewId);
            String reportText = buildReportText(report);

            // 2. 查询面试及岗位类别，作为成长内容的个性化输入。
            InterviewDetailResponse interviewDetail = interviewService.getInterview(userId, interviewId);
            String positionTitle = interviewDetail.getPositionTitle();
            String jobCategory = resolveJobCategory(interviewDetail.getPositionId());

            // 3. CoachAgent 当前只按岗位类别和薄弱点套用本地规则；reportText 参数暂未参与三参数生成逻辑。
            GrowthPlanResponse response = coachAgent.generatePlan(reportText, jobCategory, report.getWeaknesses());

            // 4. 同时保存展示用 Markdown 和便于接口返回的结构化数据，再标记为完成。
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
            // 异常继续抛出后整个事务回滚，这次 FAILED 更新及事务内新生成的报告都不会保留下来。
            plan.setStatus(GrowthPlanStatus.FAILED);
            growthPlanRepository.save(plan);
            throw e;
        }
    }

    /**
     * 把结构化报告拼成面向教练组件的文本输入。
     *
     * <p>当前三参数 {@link CoachAgent#generatePlan(String, String, java.util.List)} 实现没有读取该文本，
     * 因此这段内容目前不会影响生成结果，实际结果只受岗位类别和薄弱点列表影响。</p>
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
     * 把已完成的方案记录还原为接口响应。
     *
     * <p>优先使用保存的结构化 JSON；内容为空或解析失败时，降级为只返回方案 ID、岗位名称和 Markdown。
     * 评分、等级、学习路径、练习题和知识缺口不会在降级响应中重新补齐。</p>
     */
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

    /**
     * 序列化完整的成长方案响应，供后续直接还原结构化内容。
     *
     * <p>序列化失败时抛出运行时异常，由生成主流程记录失败并继续抛出，最终触发整个事务回滚。</p>
     */
    private String toJson(GrowthPlanResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("成长方案序列化失败", e);
        }
    }

    /**
     * 根据岗位 ID 解析岗位大类，用于 CoachAgent 选择对应的本地规则。
     *
     * <p>岗位 ID 为空、岗位不存在或分类为空时都返回 {@code GENERAL}。本方法只按岗位 ID 查询，
     * 不单独校验资源归属；当前调用方使用的是已经过面试归属校验的岗位 ID。</p>
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
