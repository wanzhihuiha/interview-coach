package com.interviewcoach.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewcoach.common.security.agent.AgentAuditLogRecord;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import com.interviewcoach.position.domain.entity.PositionAuditStatus;
import com.interviewcoach.position.domain.entity.PositionLevel;
import com.interviewcoach.position.domain.entity.PositionParseStatus;
import com.interviewcoach.resume.domain.entity.ExperienceLevel;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.user.domain.entity.UserRole;
import com.interviewcoach.user.domain.entity.UserStatus;
import org.junit.jupiter.api.Test;

/**
 * 核对跨业务域用户可见枚举的稳定英文编码与中文展示名映射。
 *
 * <p>本类同时固定已知编码的中文名称和未知历史值原样回退边界，不验证 Controller 对 API Label 的组装。</p>
 */
class DisplayNameMappingTest {

    @Test
    void shouldExposeChineseNamesForStableCodes() {
        assertThat(JobCategoryType.displayNameOf("TECH")).isEqualTo("技术类");
        assertThat(ExperienceLevel.displayNameOf("SENIOR")).isEqualTo("高级");
        assertThat(PositionLevel.displayNameOf("EXPERT")).isEqualTo("专家");
        assertThat(ResumeParseStatus.PENDING_CONFIRM.getDisplayName()).isEqualTo("待确认");
        assertThat(PositionParseStatus.PARSE_FAILED.getDisplayName()).isEqualTo("解析失败");
        assertThat(PositionAuditStatus.APPROVED.getDisplayName()).isEqualTo("已通过");
        assertThat(InterviewStatus.IN_PROGRESS.getDisplayName()).isEqualTo("进行中");
        assertThat(InterviewPhase.RESUME_DISCUSSION.getDisplayName()).isEqualTo("简历探讨");
        assertThat(AgentType.RESUME_ANALYSIS.getDisplayName()).isEqualTo("简历分析");
        assertThat(AgentAuditLogRecord.AuditStatus.ALLOWED.getDisplayName()).isEqualTo("允许");
        assertThat(UserStatus.ACTIVE.getDisplayName()).isEqualTo("正常");
        assertThat(UserRole.ADMIN.getDisplayName()).isEqualTo("管理员");
    }

    @Test
    void shouldKeepUnknownHistoricalValuesReadable() {
        assertThat(JobCategoryType.displayNameOf("CUSTOM_CATEGORY")).isEqualTo("CUSTOM_CATEGORY");
        assertThat(ExperienceLevel.displayNameOf("中高级")).isEqualTo("中高级");
        assertThat(PositionLevel.displayNameOf("资深专家")).isEqualTo("资深专家");
        assertThat(InterviewPhase.displayNameOf("CUSTOM_PHASE")).isEqualTo("CUSTOM_PHASE");
        assertThat(UserRole.displayNameOf("AUDITOR")).isEqualTo("AUDITOR");
    }
}
