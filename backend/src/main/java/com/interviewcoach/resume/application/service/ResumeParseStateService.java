package com.interviewcoach.resume.application.service;

import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理后台解析任务的短事务状态流转。
 *
 * <p>该服务通过独立 Spring Bean 被后台工作器调用，使悲观锁和事务代理真实生效；
 * 文件、Redis 和 LLM 调用均不在这些事务中。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseStateService {

    private final ResumeRepository resumeRepository;
    private final ResumeProfileSupport resumeProfileSupport;

    /**
     * 锁定简历并将待处理任务推进到解析中。
     * 重复、过期或不属于该用户的任务返回 {@code null}，调用方不会继续执行外部调用。
     */
    @Transactional
    public ParseInput start(Long resumeId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null || resume.getParseStatus() != ResumeParseStatus.PENDING) {
            log.info("[ResumeParse] 跳过非待处理任务: resumeId={}, status={}",
                    resumeId, resume == null ? "NOT_FOUND" : resume.getParseStatus());
            return null;
        }
        resume.setParseStatus(ResumeParseStatus.PARSING);
        resumeRepository.save(resume);
        log.info("[ResumeParse] 状态流转已写入当前事务: resumeId={}, from={}, to={}",
                resumeId, ResumeParseStatus.PENDING, ResumeParseStatus.PARSING);
        return new ParseInput(resume.getFilePath(), resume.getFileType());
    }

    /**
     * 原子保存画像并将仍处于解析中的任务推进到待确认。
     * 若任务已被重新处理、删除或改变状态，则返回 {@code false} 并丢弃过期结果。
     */
    @Transactional
    public boolean complete(Long resumeId, Long userId, UserProfileData profileData) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null || resume.getParseStatus() != ResumeParseStatus.PARSING) {
            log.warn("[ResumeParse] 忽略已失效的解析结果: resumeId={}, status={}",
                    resumeId, resume == null ? "NOT_FOUND" : resume.getParseStatus());
            return false;
        }
        resumeProfileSupport.saveProfile(resumeId, userId, profileData);
        resume.setJobCategory(resumeProfileSupport.inferJobCategory(profileData));
        resume.setParseStatus(ResumeParseStatus.PENDING_CONFIRM);
        resumeRepository.save(resume);
        log.info("[ResumeParse] 画像与状态已写入当前事务: resumeId={}, from={}, to={}",
                resumeId, ResumeParseStatus.PARSING, ResumeParseStatus.PENDING_CONFIRM);
        return true;
    }

    /**
     * 将仍在等待或解析中的任务标记为失败，不覆盖已经完成的状态。
     */
    @Transactional
    public void fail(Long resumeId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null) {
            log.warn("[ResumeParse] 标记失败状态时简历不存在或不属于当前用户: resumeId={}", resumeId);
            return;
        }
        if (resume.getParseStatus() == ResumeParseStatus.PENDING
                || resume.getParseStatus() == ResumeParseStatus.PARSING) {
            ResumeParseStatus previousStatus = resume.getParseStatus();
            resume.setParseStatus(ResumeParseStatus.PARSE_FAILED);
            resumeRepository.save(resume);
            log.info("[ResumeParse] 失败状态已写入当前事务: resumeId={}, from={}, to={}",
                    resumeId, previousStatus, ResumeParseStatus.PARSE_FAILED);
            return;
        }
        log.info("[ResumeParse] 跳过失败状态覆盖: resumeId={}, status={}",
                resumeId, resume.getParseStatus());
    }

    /**
     * 后台解析所需的已持久化文件信息。
     */
    public record ParseInput(String filePath, String fileType) {
    }
}
