package com.interviewcoach.interview.interfaces.rest;

import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.growth.application.dto.GrowthPlanResponse;
import com.interviewcoach.growth.application.service.GrowthPlanService;
import com.interviewcoach.interview.application.dto.CreateInterviewRequest;
import com.interviewcoach.interview.application.dto.CreateInterviewResponse;
import com.interviewcoach.interview.application.dto.InterviewDetailResponse;
import com.interviewcoach.interview.application.dto.InterviewMessageResponse;
import com.interviewcoach.interview.application.dto.InterviewReportResponse;
import com.interviewcoach.interview.application.dto.SubmitAnswerRequest;
import com.interviewcoach.interview.application.service.InterviewService;
import com.interviewcoach.interview.domain.agent.CoordinatorAgent.TurnResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 面试模块 REST 接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final GrowthPlanService growthPlanService;

    @PostMapping
    public ApiResponse<CreateInterviewResponse> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody CreateInterviewRequest request) {
        return ApiResponse.success(interviewService.createInterview(userId, request));
    }

    @GetMapping
    public ApiResponse<List<InterviewDetailResponse>> list(
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(interviewService.listInterviews(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<InterviewDetailResponse> get(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(interviewService.getInterview(userId, id));
    }

    @PostMapping("/{id}/answer")
    public SseEmitter answer(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @RequestBody SubmitAnswerRequest request,
            HttpServletResponse response) {

        // 禁用缓存并设置 SSE 响应头
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");

        SseEmitter emitter = new SseEmitter(300_000L);
        sendEvent(emitter, Map.of("type", "thinking", "content", "面试官正在思考..."));

        try {
            TurnResult result = interviewService.submitAnswer(userId, id, request.getAnswer());

            if (result.getPreviousPhase() != null) {
                sendEvent(emitter, Map.of(
                        "type", "phaseChange",
                        "previousPhase", result.getPreviousPhase().name(),
                        "currentPhase", result.getPhase().name()
                ));
            }

            sendEvent(emitter, Map.of(
                    "type", "question",
                    "content", result.getQuestion(),
                    "phase", result.getPhase().name(),
                    "depth", result.getDepth() == null ? 1 : result.getDepth(),
                    "topicId", result.getTopicId() == null ? "" : result.getTopicId(),
                    "topicName", result.getTopicName() == null ? "" : result.getTopicName()
            ));

            if (result.getPhase().name().equals("ENDING")) {
                sendEvent(emitter, Map.of("type", "interviewEnd", "content", "面试已结束"));
            }

            sendEvent(emitter, Map.of("type", "done"));
        } catch (Exception e) {
            log.error("[InterviewController] 面试回答处理失败: interviewId={}, userId={}", id, userId, e);
            sendEvent(emitter, Map.of(
                    "type", "error",
                    "code", "INTERVIEW_PROCESS_ERROR",
                    "message", e.getMessage(),
                    "fallback", true
            ));
        } finally {
            emitter.complete();
        }

        return emitter;
    }

    @PostMapping("/{id}/end")
    public ApiResponse<InterviewDetailResponse> end(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(interviewService.endInterview(userId, id));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<InterviewReportResponse> report(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(interviewService.getReport(userId, id));
    }

    /**
     * 获取或生成面试对应的成长方案。
     */
    @GetMapping("/{id}/growth-plan")
    public ApiResponse<GrowthPlanResponse> growthPlan(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(growthPlanService.getOrCreatePlan(userId, id));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<InterviewMessageResponse>> messages(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(interviewService.listMessages(userId, id));
    }

    private void sendEvent(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.warn("[InterviewController] SSE 发送失败: {}", e.getMessage());
        }
    }
}
