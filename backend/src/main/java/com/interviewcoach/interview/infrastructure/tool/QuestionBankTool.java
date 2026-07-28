package com.interviewcoach.interview.infrastructure.tool;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.interview.domain.entity.PermanentQuestionBank;
import com.interviewcoach.interview.domain.entity.TemporaryQuestionBank;
import com.interviewcoach.interview.domain.repository.PermanentQuestionBankRepository;
import com.interviewcoach.interview.domain.repository.TemporaryQuestionBankRepository;
import com.interviewcoach.interview.domain.service.QuestionSimilarityChecker;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 题库工具：支持临时 RAG 与永久 RAG 的分层管理。
 *
 * <p>权限边界：
 * <ul>
 *   <li>临时 RAG：仅评估 Agent 可读写。</li>
 *   <li>永久 RAG：面试官、评估、协调、报告等 Agent 可读。</li>
 *   <li>临时题提升为永久题：由管理员审核后通过应用服务完成，不开放给 Agent。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionBankTool {

    private final TemporaryQuestionBankRepository temporaryRepository;
    private final PermanentQuestionBankRepository permanentRepository;
    private final QuestionSimilarityChecker similarityChecker;

    /**
     * 将生成的新题存入临时 RAG，存入前会先与临时和永久题库去重。
     *
     * @param item 题目信息
     * @return 保存后的题目 ID；如已存在重复题则返回空
     */
    @AgentPermission(AgentType.EVALUATOR)
    public Optional<Long> saveTemporaryQuestion(QuestionBankItem item) {
        if (item == null || item.getContent() == null || item.getContent().isBlank()) {
            log.warn("[QuestionBankTool] 题目内容为空，跳过保存");
            return Optional.empty();
        }

        String normalized = normalizeContent(item.getContent());
        if (existsDuplicate(normalized, item.getJobCategory(), item.getPhase())) {
            log.info("[QuestionBankTool] 题目已存在，跳过保存: jobCategory={}, phase={}",
                    item.getJobCategory(), item.getPhase());
            return Optional.empty();
        }

        TemporaryQuestionBank entity = new TemporaryQuestionBank();
        entity.setJobCategory(item.getJobCategory());
        entity.setPhase(item.getPhase());
        entity.setTopicId(item.getTopicId());
        entity.setTopicName(item.getTopicName());
        entity.setContent(item.getContent());
        entity.setExpectedAnswer(item.getExpectedAnswer());
        entity.setSourceInterviewId(item.getId());
        entity.setStatus("PENDING");
        entity.setDifficultyLevel(item.getDifficultyLevel() != null ? item.getDifficultyLevel() : 3);

        temporaryRepository.save(entity);
        log.info("[QuestionBankTool] 新题已存入临时 RAG: id={}, jobCategory={}, phase={}",
                entity.getId(), entity.getJobCategory(), entity.getPhase());
        return Optional.of(entity.getId());
    }

    /**
     * 检查指定题目在临时 RAG 和永久 RAG 中是否已存在（基于文本相似度）。
     */
    @AgentPermission(AgentType.EVALUATOR)
    public boolean existsDuplicate(String content, String jobCategory, String phase) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = normalizeContent(content);

        // 1. 精确匹配
        boolean exactInTemporary = !temporaryRepository.findByContent(normalized).isEmpty();
        boolean exactInPermanent = !permanentRepository.findByContent(normalized).isEmpty();
        if (exactInTemporary || exactInPermanent) {
            return true;
        }

        // 2. 相似度匹配：检查同一岗位类别+环节下的题目
        List<TemporaryQuestionBank> temporaryCandidates = temporaryRepository
                .findByJobCategoryAndPhaseAndStatus(jobCategory, phase, "PENDING");
        for (TemporaryQuestionBank candidate : temporaryCandidates) {
            if (similarityChecker.isDuplicate(content, candidate.getContent())) {
                return true;
            }
        }

        List<PermanentQuestionBank> permanentCandidates = permanentRepository
                .findByJobCategoryAndPhaseOrderByUsageCountAsc(jobCategory, phase);
        for (PermanentQuestionBank candidate : permanentCandidates) {
            if (similarityChecker.isDuplicate(content, candidate.getContent())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 从永久 RAG 中按岗位类别和环节采样题目。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.COORDINATOR, AgentType.REPORT})
    public List<QuestionBankItem> sampleFromPermanent(String jobCategory, String phase, int limit) {
        List<PermanentQuestionBank> entities = permanentRepository
                .findByJobCategoryAndPhaseOrderByUsageCountAsc(jobCategory, phase);
        return entities.stream()
                .limit(limit)
                .map(this::toItem)
                .toList();
    }

    /**
     * 从永久 RAG 中按岗位类别、环节、主题采样题目。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.COORDINATOR, AgentType.REPORT})
    public List<QuestionBankItem> sampleFromPermanent(String jobCategory, String phase, String topicId, int limit) {
        List<PermanentQuestionBank> entities = permanentRepository
                .findByJobCategoryAndPhaseAndTopicIdOrderByUsageCountAsc(jobCategory, phase, topicId);
        return entities.stream()
                .limit(limit)
                .map(this::toItem)
                .toList();
    }

    /**
     * 按岗位大类 + 主题统计永久题库题目数。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.COORDINATOR, AgentType.REPORT})
    public long countByJobCategoryAndTopic(String jobCategory, String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return permanentRepository.findByJobCategoryAndPhaseOrderByUsageCountAsc(jobCategory, "PROFESSIONAL")
                    .size();
        }
        return permanentRepository.findByJobCategoryAndPhaseAndTopicIdOrderByUsageCountAsc(
                jobCategory, "PROFESSIONAL", topicId).size();
    }

    /**
     * 决定本次出题来源中永久题库所占比例。MVP 阶段永久题库较少时返回 0.0，后续可按题目数量动态调整。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.COORDINATOR, AgentType.REPORT})
    public double decideBankRatio(String jobCategory, String topicId) {
        long count = countByJobCategoryAndTopic(jobCategory, topicId);
        if (count < 10) {
            return 0.0;
        }
        if (count < 100) {
            return 0.2;
        }
        if (count < 200) {
            return 0.4;
        }
        if (count < 400) {
            return 0.7;
        }
        if (count < 1000) {
            return 0.85;
        }
        return 0.95;
    }

    private QuestionBankItem toItem(PermanentQuestionBank entity) {
        QuestionBankItem item = new QuestionBankItem();
        item.setId(entity.getId());
        item.setJobCategory(entity.getJobCategory());
        item.setPhase(entity.getPhase());
        item.setTopicId(entity.getTopicId());
        item.setTopicName(entity.getTopicName());
        item.setContent(entity.getContent());
        item.setExpectedAnswer(entity.getExpectedAnswer());
        item.setUsageCount(entity.getUsageCount());
        item.setDifficultyLevel(entity.getDifficultyLevel() != null ? entity.getDifficultyLevel() : 3);
        return item;
    }

    private String normalizeContent(String content) {
        return content.trim().replaceAll("\\s+", " ");
    }
}
