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

    /**
     * 以 SSE 事件包装一轮回答的处理结果。
     *
     * <p>当前实现会先发送“思考中”事件，再在本请求线程同步完成回答评估、流程决策和数据保存，
     * 随后根据完整结果发送环节变化、下一题、面试结束和完成事件，并在方法返回前把 Emitter 标记为完成。
     * 方法返回前 Emitter 还没有交给 Spring MVC，这些事件会先被缓存，再由框架统一写入响应，
     * 因此前端不一定能先看到“思考中”再等待结果，这里也不是把任务放到后台持续输出的流式执行。</p>
     *
     * <p>本方法会捕获业务处理中的所有异常，记录日志后改发通用的 SSE 错误事件，不再交给统一异常处理器
     * 生成常规错误响应。SSE 单次发送发生 {@link IOException} 时只记录警告，不会取消已经开始的业务处理。</p>
     */
    @PostMapping("/{id}/answer")
    public SseEmitter answer(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @RequestBody SubmitAnswerRequest request,
            HttpServletResponse response) {

        // 1. 设置 SSE 响应头，避免代理缓存或合并事件。
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");

        // 2. 先发送思考状态；后续业务处理仍在当前请求线程内同步完成。
        SseEmitter emitter = new SseEmitter(300_000L);
        sendEvent(emitter, Map.of("type", "thinking", "content", "面试官正在思考..."));

        try {
            // 3. Service 完成整轮处理并返回最终结果，包括可能发生的环节变化。
            TurnResult result = interviewService.submitAnswer(userId, id, request.getAnswer());

            // 4. 将处理结果转换成前端约定的 SSE 事件，并在本次请求内一次性发送完毕。
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
            // 业务异常在接口层统一转成 SSE 错误事件，HTTP 响应不再走常规异常处理流程。
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

    /**
     * 获取当前报告；报告不存在时会在本次 GET 请求中同步分析、脱敏并写入数据库。
     */
    @GetMapping("/{id}/report")
    public ApiResponse<InterviewReportResponse> report(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(interviewService.getReport(userId, id));
    }

    /**
     * 获取或生成面试对应的成长方案。
     *
     * <p>这不是纯查询：方案不存在或未完成时，会在本次 GET 请求中同步生成并保存；
     * 依赖的面试报告不存在时，还会先生成并保存报告。</p>
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

    /**
     * 尽力发送单个 SSE 事件。
     *
     * <p>发送发生 {@link IOException} 时只记录警告，不向调用方抛出异常，因此后续事件和业务处理仍会继续；
     * 客户端断开也不会通过本方法自动取消已经开始的回答处理。</p>
     */
    private void sendEvent(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.warn("[InterviewController] SSE 发送失败: {}", e.getMessage());
        }
    }
}
