package com.interviewcoach.resume.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.resume.application.dto.ResumeProfileAnalysisRetryRequest;
import com.interviewcoach.resume.application.service.ResumeService;
import org.junit.jupiter.api.Test;

/**
 * 验证简历 Controller 将认证用户 ID、路径简历 ID 和辅助分析重试请求原样交给应用服务，并使用统一响应包装。
 *
 * <p>测试直接构造 Controller 和服务 Mock，不经过 Spring MVC 参数解析；缺失请求体是否有效由下游业务校验决定。</p>
 */
class ResumeControllerTest {

    @Test
    void shouldPassAnalysisModeAndFeedbackToService() {
        ResumeService resumeService = mock(ResumeService.class);
        ResumeController controller = new ResumeController(resumeService);
        ResumeProfileAnalysisRetryRequest request =
                new ResumeProfileAnalysisRetryRequest("REFINE", "关注工程能力证据");

        ApiResponse<Void> response = controller.retryProfileAnalysis(1L, 2L, request);

        verify(resumeService).retryProfileAnalysis(1L, 2L, request);
        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isNull();
    }

    @Test
    void shouldPassMissingBodyToBusinessValidation() {
        ResumeService resumeService = mock(ResumeService.class);
        ResumeController controller = new ResumeController(resumeService);

        controller.retryProfileAnalysis(1L, 2L, null);

        verify(resumeService).retryProfileAnalysis(1L, 2L, null);
    }
}
