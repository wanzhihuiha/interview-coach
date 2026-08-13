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
 * Agent 访问临时题库和永久题库的受限工具。
 *
 * <p>权限边界：
 * <ul>
 *   <li>临时题库：评估 Agent 可通过本工具附带写入和执行去重查询。</li>
 *   <li>永久题库：面试官、评估、协调、报告 Agent 可读取。</li>
 *   <li>临时题提升为永久题：由管理员审核后通过应用服务完成，不开放给 Agent。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionBankTool {

    /** 负责临时题保存、精确正文查询和待审核候选题读取。 */
    private final TemporaryQuestionBankRepository temporaryRepository;
    /** 负责永久题精确正文查询及按使用次数升序读取候选题。 */
    private final PermanentQuestionBankRepository permanentRepository;
    /** 负责同岗位类别和环节候选题之间的字符相似判重。 */
    private final QuestionSimilarityChecker similarityChecker;

    /**
     * 将本轮实际问题存入临时题库，存入前先跨临时和永久题库去重。
     *
     * <p>DTO 的 {@code id} 在此入口临时承载来源面试 ID；空内容或重复题返回空。难度为空时
     * 写入当前固定默认 3，其精确依据缺失。</p>
     *
     * @param item Evaluator 组装的题目信息
     * @return 保存后的题目 ID；如已存在重复题则返回空
     */
    @AgentPermission(AgentType.EVALUATOR)
    public Optional<Long> saveTemporaryQuestion(QuestionBankItem item) {
        if (item == null || item.getContent() == null || item.getContent().isBlank()) {
            log.warn("[QuestionBankTool] 题目内容为空，跳过保存");
            return Optional.empty();
        }

        // 折叠空白后执行跨两类题库的精确和相似度检查，重复时不产生数据库写入。
        String normalized = normalizeContent(item.getContent());
        if (existsDuplicate(normalized, item.getJobCategory(), item.getPhase())) {
            log.info("[QuestionBankTool] 题目已存在，跳过保存: jobCategory={}, phase={}",
                    item.getJobCategory(), item.getPhase());
            return Optional.empty();
        }

        // 将 DTO 映射为待审核实体；来源面试 ID 来自复用 DTO 的 id 字段。
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

        // 保存参加调用方当前事务；数据库异常向 Evaluator 传播并由其隔离为题库附带写入失败。
        temporaryRepository.save(entity);
        log.info("[QuestionBankTool] 新题已存入临时 RAG: id={}, jobCategory={}, phase={}",
                entity.getId(), entity.getJobCategory(), entity.getPhase());
        return Optional.of(entity.getId());
    }

    /**
     * 检查指定题目是否已存在。
     *
     * <p>先用折叠空白后的正文对两表做全局精确查询，再只在相同岗位类别和环节内比较待审核
     * 临时题与全部永久题的字符相似度。空内容返回 false。</p>
     */
    @AgentPermission(AgentType.EVALUATOR)
    public boolean existsDuplicate(String content, String jobCategory, String phase) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = normalizeContent(content);

        // 精确查询不带岗位或环节条件，因此跨分层相同正文也会判重。
        boolean exactInTemporary = !temporaryRepository.findByContent(normalized).isEmpty();
        boolean exactInPermanent = !permanentRepository.findByContent(normalized).isEmpty();
        if (exactInTemporary || exactInPermanent) {
            return true;
        }

        // 相似度阶段只读取相同岗位类别、环节且状态为 PENDING 的临时题。
        List<TemporaryQuestionBank> temporaryCandidates = temporaryRepository
                .findByJobCategoryAndPhaseAndStatus(jobCategory, phase, "PENDING");
        for (TemporaryQuestionBank candidate : temporaryCandidates) {
            if (similarityChecker.isDuplicate(content, candidate.getContent())) {
                return true;
            }
        }

        // 永久题按 usageCount 升序读取全部匹配项，但遍历判重不依赖该顺序。
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
     * 按岗位类别和环节读取永久题，并在内存中截取前 {@code limit} 条。
     * 仓储按 {@code usageCount} 升序；本方法不随机、不累加使用次数，负 limit 会由 Stream 拒绝。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.COORDINATOR, AgentType.REPORT})
    public List<QuestionBankItem> sampleFromPermanent(String jobCategory, String phase, int limit) {
        // 数据库返回完整升序集合，limit 只在 JVM 内存流上应用。
        List<PermanentQuestionBank> entities = permanentRepository
                .findByJobCategoryAndPhaseOrderByUsageCountAsc(jobCategory, phase);
        return entities.stream()
                .limit(limit)
                .map(this::toItem)
                .toList();
    }

    /**
     * 按岗位类别、环节和主题读取永久题，并在内存中截取使用次数最少的前 {@code limit} 条。
     * 本方法同样不随机也不写回使用次数。
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
     * 统计指定岗位类别下专业环节永久题数；主题为空统计整个专业环节，否则精确筛选主题。
     * 当前实现通过加载实体列表后取 size，不执行数据库 count 查询。
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
     * 按永久题数量返回分段比例值。
     *
     * <p>当前 Interviewer 只判断返回值是否大于 0，把它当作题库启用开关，并未按 0.2～0.95
     * 概率选择来源。10/100/200/400/1000 和各比例的精确依据缺失。</p>
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

    /** 将永久实体映射为 Agent DTO；难度为空时按当前固定默认 3 返回。 */
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

    /** 去除正文两端空白并把连续空白折叠成一个空格，供精确查询和相似度入口使用。 */
    private String normalizeContent(String content) {
        return content.trim().replaceAll("\\s+", " ");
    }
}
