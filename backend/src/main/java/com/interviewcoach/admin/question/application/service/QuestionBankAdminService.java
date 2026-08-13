package com.interviewcoach.admin.question.application.service;

import static com.interviewcoach.interview.application.service.QuestionBankErrorCode.*;

import com.interviewcoach.common.domain.JobCategoryType;
import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.interview.application.dto.QuestionBankListResponse;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.interview.domain.entity.PermanentQuestionBank;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.entity.TemporaryQuestionBank;
import com.interviewcoach.interview.domain.repository.PermanentQuestionBankRepository;
import com.interviewcoach.interview.domain.repository.TemporaryQuestionBankRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员审核临时 RAG 题目并维护永久题库的应用服务。
 *
 * <p>由受 ADMIN 角色保护的题库接口调用，负责待审查询、永久库分页、审核状态守卫、
 * 精确内容去重以及临时题目到永久题目的字段映射；本服务自身不重复校验管理员角色。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionBankAdminService {

    /**
     * 读取和写入 LLM 生成后等待管理员审核的临时题目。
     */
    private final TemporaryQuestionBankRepository temporaryRepository;

    /**
     * 查询永久题库并保存审核通过的题目。
     */
    private final PermanentQuestionBankRepository permanentRepository;

    /**
     * 查询待审核临时题目，不执行分页。
     *
     * <p>岗位类别和面试环节只有同时为非空白值时才共同参与数据库查询；只提供其中一个、
     * 两者都缺失或任一为空白时，当前实现会读取全部临时题目后仅保留 PENDING，单独提供的
     * 筛选值不会生效。</p>
     *
     * @param jobCategory 岗位类别编码，必须与 {@code phase} 同时提供才生效
     * @param phase       面试环节编码，必须与 {@code jobCategory} 同时提供才生效
     * @return 待审临时题目的管理端 DTO 列表
     */
    @Transactional(readOnly = true)
    public List<QuestionBankItem> listPendingQuestions(String jobCategory, String phase) {
        List<TemporaryQuestionBank> entities;
        if (jobCategory != null && !jobCategory.isBlank() && phase != null && !phase.isBlank()) {
            // 两个筛选均完整时由数据库按岗位、环节和 PENDING 状态精确查询。
            entities = temporaryRepository.findByJobCategoryAndPhaseAndStatus(jobCategory, phase, "PENDING");
        } else {
            // 任一筛选缺失时读取全部临时题目，再仅按 PENDING 状态在内存中过滤。
            entities = temporaryRepository.findAll().stream()
                    .filter(e -> "PENDING".equals(e.getStatus()))
                    .toList();
        }
        // 将题目编码转换为“稳定编码+中文 Label”的管理端结构，并补当前默认难度。
        return entities.stream().map(this::toItem).toList();
    }

    /**
     * 分页查询永久题库内容，支持按岗位类别、环节和关键字过滤。
     *
     * <p>页码为零基。空白关键字会规范为 {@code null} 以关闭模糊查询；岗位类别和环节
     * 不做空白归一化，只有 {@code null} 会在仓储查询中关闭对应条件。仓储按创建时间倒序返回。</p>
     *
     * @param jobCategory 岗位类别精确筛选；{@code null} 表示不筛选
     * @param phase       面试环节精确筛选；{@code null} 表示不筛选
     * @param keyword     主题名称或题目内容的模糊筛选；空白值按未提供处理
     * @param page        零基页码
     * @param size        每页记录数
     * @return 当前页永久题目及仓储分页元数据
     */
    @Transactional(readOnly = true)
    public QuestionBankListResponse listPermanentQuestions(
            String jobCategory, String phase, String keyword, int page, int size) {
        // 将零基页码和页大小交给 Spring Data；非法分页值由 PageRequest 直接拒绝。
        Pageable pageable = PageRequest.of(page, size);
        String keywordParam = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        // 仓储组合可选精确条件和关键字模糊条件，并按创建时间倒序读取永久题目。
        Page<PermanentQuestionBank> pageResult = permanentRepository.findPermanentQuestions(
                jobCategory, phase, keywordParam, pageable);

        QuestionBankListResponse response = new QuestionBankListResponse();
        // 映射当前页题目并保留数据库查询返回的总数、总页数和零基页码。
        response.setContent(pageResult.getContent().stream().map(this::toItem).toList());
        response.setTotalElements(pageResult.getTotalElements());
        response.setTotalPages(pageResult.getTotalPages());
        response.setCurrentPage(pageResult.getNumber());
        return response;
    }

    /**
     * 将待审核临时题目复制到永久题库并把临时记录标记为已通过。
     *
     * <p>操作在同一事务内按“加载临时题目、校验 PENDING、永久库内容精确去重、保存永久题目、
     * 保存临时 APPROVED 状态”的顺序执行。题目不存在、状态不允许或内容重复时抛出题库业务
     * 异常；运行时业务异常会回滚本事务内已经执行的保存。</p>
     *
     * @param temporaryQuestionId 待晋升的临时题目 ID
     * @return 新永久题目的数据库 ID
     */
    @Transactional
    public Long promoteToPermanent(Long temporaryQuestionId) {
        // 先从临时库加载目标；不存在时立即以 6201 结束，不创建永久题目。
        TemporaryQuestionBank temporary = temporaryRepository.findById(temporaryQuestionId)
                .orElseThrow(() -> new BusinessException(QUESTION_NOT_FOUND.getCode(), "临时题目不存在"));
        if (!"PENDING".equals(temporary.getStatus())) {
            throw new BusinessException(QUESTION_STATUS_INVALID.getCode(), "仅待审核题目可加入永久题库");
        }

        // 加入永久库前按题目正文精确查询，防止审核阶段再次写入同内容永久题目。
        List<PermanentQuestionBank> duplicates = permanentRepository.findByContent(temporary.getContent());
        if (!duplicates.isEmpty()) {
            temporary.setStatus("REJECTED");
            // 当前分支保存后会抛运行时业务异常，默认事务回滚会撤销这次 REJECTED 持久化。
            temporaryRepository.save(temporary);
            throw new BusinessException(QUESTION_DUPLICATE.getCode(), "永久题库中已存在相同题目");
        }

        PermanentQuestionBank permanent = new PermanentQuestionBank();
        permanent.setJobCategory(temporary.getJobCategory());
        permanent.setPhase(temporary.getPhase());
        permanent.setTopicId(temporary.getTopicId());
        permanent.setTopicName(temporary.getTopicName());
        permanent.setContent(temporary.getContent());
        permanent.setExpectedAnswer(temporary.getExpectedAnswer());
        permanent.setSourceTemporaryId(temporary.getId());
        permanent.setUsageCount(0);
        // 临时题目未给难度时写入与实体及数据库默认一致的 3；依据缺失，改动会改变晋升题目的初值。
        permanent.setDifficultyLevel(temporary.getDifficultyLevel() != null ? temporary.getDifficultyLevel() : 3);
        // 先保存永久题目以取得 ID；后续临时状态保存失败时同一事务会整体回滚。
        permanentRepository.save(permanent);

        temporary.setStatus("APPROVED");
        // 永久题目写入成功后再持久化临时题目的 APPROVED 状态，二者共享事务完成线。
        temporaryRepository.save(temporary);

        log.info("[QuestionBankAdminService] 临时题目已加入永久 RAG: temporaryId={}, permanentId={}",
                temporaryQuestionId, permanent.getId());
        return permanent.getId();
    }

    /**
     * 将待审核临时题目标记为已拒绝。
     *
     * @param temporaryQuestionId 待拒绝的临时题目 ID
     */
    @Transactional
    public void rejectTemporaryQuestion(Long temporaryQuestionId) {
        // 先读取目标并区分不存在与状态不允许，失败时不执行状态写入。
        TemporaryQuestionBank temporary = temporaryRepository.findById(temporaryQuestionId)
                .orElseThrow(() -> new BusinessException(QUESTION_NOT_FOUND.getCode(), "临时题目不存在"));
        if (!"PENDING".equals(temporary.getStatus())) {
            throw new BusinessException(QUESTION_STATUS_INVALID.getCode(), "仅待审核题目可拒绝");
        }
        temporary.setStatus("REJECTED");
        // 只有仍为 PENDING 的题目才保存 REJECTED，保存异常会使事务回滚。
        temporaryRepository.save(temporary);
        log.info("[QuestionBankAdminService] 临时题目已拒绝: temporaryId={}", temporaryQuestionId);
    }

    /**
     * 更新待审核临时题目的主题名称、正文、参考答案及可选难度。
     *
     * <p>正文必填并去除首尾空白；主题名称仅在请求非 {@code null} 时更新并去除首尾空白，
     * 参考答案按请求原值写入。难度非空时被压到当前固定的 1～5 区间，而不是拒绝越界输入。
     * 该区间精确取值的产品依据缺失，调整边界会改变管理端编辑值的最终落库结果。</p>
     *
     * @param temporaryQuestionId 待编辑的临时题目 ID
     * @param item                管理端提交的可编辑题目字段
     */
    @Transactional
    public void updateTemporaryQuestion(Long temporaryQuestionId, QuestionBankItem item) {
        // 先加载目标并要求仍为 PENDING，避免编辑已通过或已拒绝的历史题目。
        TemporaryQuestionBank temporary = temporaryRepository.findById(temporaryQuestionId)
                .orElseThrow(() -> new BusinessException(QUESTION_NOT_FOUND.getCode(), "临时题目不存在"));
        if (!"PENDING".equals(temporary.getStatus())) {
            throw new BusinessException(QUESTION_STATUS_INVALID.getCode(), "仅待审核题目可编辑");
        }
        if (item.getContent() == null || item.getContent().isBlank()) {
            throw new BusinessException(QUESTION_CONTENT_EMPTY.getCode(), "题目内容不能为空");
        }
        if (item.getTopicName() != null) {
            temporary.setTopicName(item.getTopicName().trim());
        }
        temporary.setContent(item.getContent().trim());
        temporary.setExpectedAnswer(item.getExpectedAnswer());
        if (item.getDifficultyLevel() != null) {
            // 当前固定把管理端输入压到 1～5；精确产品依据缺失，越界值不会触发参数错误。
            temporary.setDifficultyLevel(Math.max(1, Math.min(5, item.getDifficultyLevel())));
        }
        // 在事务内保存编辑结果；仓储异常会阻止本次更新提交。
        temporaryRepository.save(temporary);
        log.info("[QuestionBankAdminService] 临时题目已更新: temporaryQuestionId={}", temporaryQuestionId);
    }

    /**
     * 将临时题目映射为管理端 DTO。
     *
     * <p>岗位类别和面试环节同时返回原始稳定编码及中文 Label；未知历史编码的 Label
     * 保留原值。临时题目没有使用次数，DTO 的 {@code usageCount} 保持 {@code null}；
     * 难度为空时按与数据库默认一致的当前值 3 返回。</p>
     */
    private QuestionBankItem toItem(TemporaryQuestionBank entity) {
        QuestionBankItem item = new QuestionBankItem();
        item.setId(entity.getId());
        // 展示映射对已知编码返回中文名称，对未知历史编码保留原值，避免管理端丢失信息。
        item.setJobCategory(entity.getJobCategory());
        item.setJobCategoryLabel(JobCategoryType.displayNameOf(entity.getJobCategory()));
        item.setPhase(entity.getPhase());
        item.setPhaseLabel(InterviewPhase.displayNameOf(entity.getPhase()));
        item.setTopicId(entity.getTopicId());
        item.setTopicName(entity.getTopicName());
        item.setContent(entity.getContent());
        item.setExpectedAnswer(entity.getExpectedAnswer());
        // 当前响应兜底与实体及数据库默认 3 一致；依据缺失，改动会改变空难度题目的展示结果。
        item.setDifficultyLevel(entity.getDifficultyLevel() != null ? entity.getDifficultyLevel() : 3);
        return item;
    }

    /**
     * 将永久题目映射为管理端 DTO。
     *
     * <p>除稳定编码和中文 Label 外，还返回永久库累计使用次数；难度为空时按与数据库默认
     * 一致的当前值 3 返回，精确产品依据缺失。</p>
     */
    private QuestionBankItem toItem(PermanentQuestionBank entity) {
        QuestionBankItem item = new QuestionBankItem();
        item.setId(entity.getId());
        // 展示映射对已知编码返回中文名称，对未知历史编码保留原值，避免管理端丢失信息。
        item.setJobCategory(entity.getJobCategory());
        item.setJobCategoryLabel(JobCategoryType.displayNameOf(entity.getJobCategory()));
        item.setPhase(entity.getPhase());
        item.setPhaseLabel(InterviewPhase.displayNameOf(entity.getPhase()));
        item.setTopicId(entity.getTopicId());
        item.setTopicName(entity.getTopicName());
        item.setContent(entity.getContent());
        item.setExpectedAnswer(entity.getExpectedAnswer());
        item.setUsageCount(entity.getUsageCount());
        // 当前响应兜底与实体及数据库默认 3 一致；依据缺失，改动会改变空难度题目的展示结果。
        item.setDifficultyLevel(entity.getDifficultyLevel() != null ? entity.getDifficultyLevel() : 3);
        return item;
    }
}
