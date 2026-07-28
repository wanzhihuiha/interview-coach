package com.interviewcoach.admin.question.application.service;

import static com.interviewcoach.interview.application.service.QuestionBankErrorCode.*;

import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.interview.domain.entity.PermanentQuestionBank;
import com.interviewcoach.interview.domain.entity.TemporaryQuestionBank;
import com.interviewcoach.interview.domain.repository.PermanentQuestionBankRepository;
import com.interviewcoach.interview.domain.repository.TemporaryQuestionBankRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 题库管理应用服务：管理员审核临时 RAG 题目并维护永久 RAG。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionBankAdminService {

    private final TemporaryQuestionBankRepository temporaryRepository;
    private final PermanentQuestionBankRepository permanentRepository;

    /**
     * 查询待审核的临时题目列表。
     */
    @Transactional(readOnly = true)
    public List<QuestionBankItem> listPendingQuestions(String jobCategory, String phase) {
        List<TemporaryQuestionBank> entities;
        if (jobCategory != null && !jobCategory.isBlank() && phase != null && !phase.isBlank()) {
            entities = temporaryRepository.findByJobCategoryAndPhaseAndStatus(jobCategory, phase, "PENDING");
        } else {
            entities = temporaryRepository.findAll().stream()
                    .filter(e -> "PENDING".equals(e.getStatus()))
                    .toList();
        }
        return entities.stream().map(this::toItem).toList();
    }

    /**
     * 将指定的临时题目审核通过并加入永久 RAG。
     */
    @Transactional
    public Long promoteToPermanent(Long temporaryQuestionId) {
        TemporaryQuestionBank temporary = temporaryRepository.findById(temporaryQuestionId)
                .orElseThrow(() -> new BusinessException(QUESTION_NOT_FOUND.getCode(), "临时题目不存在"));
        if (!"PENDING".equals(temporary.getStatus())) {
            throw new BusinessException(QUESTION_STATUS_INVALID.getCode(), "仅待审核题目可加入永久题库");
        }

        // 加入永久库前再次与永久库去重
        List<PermanentQuestionBank> duplicates = permanentRepository.findByContent(temporary.getContent());
        if (!duplicates.isEmpty()) {
            temporary.setStatus("REJECTED");
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
        permanentRepository.save(permanent);

        temporary.setStatus("APPROVED");
        temporaryRepository.save(temporary);

        log.info("[QuestionBankAdminService] 临时题目已加入永久 RAG: temporaryId={}, permanentId={}",
                temporaryQuestionId, permanent.getId());
        return permanent.getId();
    }

    /**
     * 拒绝指定的临时题目。
     */
    @Transactional
    public void rejectTemporaryQuestion(Long temporaryQuestionId) {
        TemporaryQuestionBank temporary = temporaryRepository.findById(temporaryQuestionId)
                .orElseThrow(() -> new BusinessException(QUESTION_NOT_FOUND.getCode(), "临时题目不存在"));
        if (!"PENDING".equals(temporary.getStatus())) {
            throw new BusinessException(QUESTION_STATUS_INVALID.getCode(), "仅待审核题目可拒绝");
        }
        temporary.setStatus("REJECTED");
        temporaryRepository.save(temporary);
        log.info("[QuestionBankAdminService] 临时题目已拒绝: temporaryId={}", temporaryQuestionId);
    }

    private QuestionBankItem toItem(TemporaryQuestionBank entity) {
        QuestionBankItem item = new QuestionBankItem();
        item.setId(entity.getId());
        item.setJobCategory(entity.getJobCategory());
        item.setPhase(entity.getPhase());
        item.setTopicId(entity.getTopicId());
        item.setTopicName(entity.getTopicName());
        item.setContent(entity.getContent());
        item.setExpectedAnswer(entity.getExpectedAnswer());
        return item;
    }
}
