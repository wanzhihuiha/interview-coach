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
 * 当前登录用户的面试 HTTP 与 SSE 接口适配器。
 *
 * <p>用户 ID 由 Spring Security 的认证主体提供，不接受请求体身份字段；应用服务负责资源归属、
 * 状态、事务和模型编排。本控制器把同步回答处理结果按固定 SSE 事件顺序发送，并提供详情、
 * 历史、报告、成长方案和消息查询。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/interviews")
@RequiredArgsConstructor
public class InterviewController {

    /** 负责创建、回答、结束、详情、报告和消息等面试用例。 */
    private final InterviewService interviewService;
    /** 负责按当前用户和面试报告获取或首次生成成长方案。 */
    private final GrowthPlanService growthPlanService;

    /**
     * 使用认证用户、简历/岗位 ID 和环节选择创建会话；首题已落库后返回创建结果。
     */
    @PostMapping
    public ApiResponse<CreateInterviewResponse> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody CreateInterviewRequest request) {
        // 应用服务复核简历/岗位归属与画像、占槽并生成首题，异常交给统一异常处理器。
        return ApiResponse.success(interviewService.createInterview(userId, request));
    }

    /** 返回认证用户的全部面试，按创建时间倒序。 */
    @GetMapping
    public ApiResponse<List<InterviewDetailResponse>> list(
            @AuthenticationPrincipal Long userId) {
        // userId 来自可信安全上下文，应用服务的仓储查询据此隔离历史会话。
        return ApiResponse.success(interviewService.listInterviews(userId));
    }

    /** 按认证用户归属返回指定面试详情；内部 reservation 不会出现在响应中。 */
    @GetMapping("/{id}")
    public ApiResponse<InterviewDetailResponse> get(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        // 应用服务同时限定面试 ID 和当前用户，越权与不存在使用相同业务失败。
        return ApiResponse.success(interviewService.getInterview(userId, id));
    }

    /**
     * 同步处理一轮回答，再通过单个 SSE emitter 依次发送思考、可选环节切换、问题、可选结束和 done。
     *
     * <p>300000 毫秒是当前固定 emitter 超时，精确依据缺失。服务调用在请求线程内完成，不会
     * 按问题 token 真正流式传输；业务或发送失败不会在本控制器重试。</p>
     */
    @PostMapping("/{id}/answer")
    public SseEmitter answer(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @RequestBody SubmitAnswerRequest request,
            HttpServletResponse response) {

        // 禁用中间层缓存与代理缓冲，并固定 UTF-8 SSE 响应类型。
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");

        /** 当前请求生命周期内发送事件的 emitter；固定超时 300000 毫秒，精确依据缺失。 */
        SseEmitter emitter = new SseEmitter(300_000L);
        // 先同步发送 thinking；底层发送失败只记录告警，流程仍继续调用应用服务。
        sendEvent(emitter, Map.of("type", "thinking", "content", "面试官正在思考..."));

        try {
            // 同步完成 reservation、评估/出题和数据库双写；返回时下一题已经持久化。
            TurnResult result = interviewService.submitAnswer(userId, id, request.getAnswer());

            if (result.getPreviousPhase() != null) {
                // 发生环节切换时，在新问题事件前先发送旧/新英文编码及中文 Label。
                sendEvent(emitter, Map.of(
                        "type", "phaseChange",
                        "previousPhase", result.getPreviousPhase().name(),
                        "previousPhaseLabel", result.getPreviousPhase().getDisplayName(),
                        "currentPhase", result.getPhase().name(),
                        "currentPhaseLabel", result.getPhase().getDisplayName()
                ));
            }

            // 发送完整问题及新环节、主题、深度快照；空主题转换为空串，空深度展示为 1。
            sendEvent(emitter, Map.of(
                    "type", "question",
                    "content", result.getQuestion(),
                    "phase", result.getPhase().name(),
                    "phaseLabel", result.getPhase().getDisplayName(),
                    "depth", result.getDepth() == null ? 1 : result.getDepth(),
                    "topicId", result.getTopicId() == null ? "" : result.getTopicId(),
                    "topicName", result.getTopicName() == null ? "" : result.getTopicName()
            ));

            if (result.getPhase().name().equals("ENDING")) {
                // 进入结束环节时在 question 之后追加前端结束信号。
                sendEvent(emitter, Map.of("type", "interviewEnd", "content", "面试已结束"));
            }

            // 正常事件序列最后发送 done；sendEvent 内的 I/O 失败不会中断后续发送。
            sendEvent(emitter, Map.of("type", "done"));
        } catch (Exception e) {
            // 业务、模型或数据库异常统一转换为 SSE error；当前会把异常消息直接放入事件。
            log.error("[InterviewController] 面试回答处理失败: interviewId={}, userId={}", id, userId, e);
            sendEvent(emitter, Map.of(
                    "type", "error",
                    "code", "INTERVIEW_PROCESS_ERROR",
                    "message", e.getMessage(),
                    "fallback", true
            ));
        } finally {
            // 无论成功或失败都同步完成 emitter；没有注册异步回调或重试任务。
            emitter.complete();
        }

        return emitter;
    }

    /** 主动结束本人面试，清理资源锁和 Redis 槽位后返回终态详情。 */
    @PostMapping("/{id}/end")
    public ApiResponse<InterviewDetailResponse> end(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        // 应用服务按 userId 加锁查询，越权请求不会触及其他用户的会话或资源锁。
        return ApiResponse.success(interviewService.endInterview(userId, id));
    }

    /**
     * 获取本人面试报告；首次 GET 会生成、执行有限字段替换并写入数据库，后续读取缓存报告。
     */
    @GetMapping("/{id}/report")
    public ApiResponse<InterviewReportResponse> report(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        // 应用服务先校验面试归属，再访问只按 interviewId 保存的报告实体。
        return ApiResponse.success(interviewService.getReport(userId, id));
    }

    /**
     * 获取或生成本人面试对应的成长方案；首次 GET 可能基于报告产生数据库写入。
     */
    @GetMapping("/{id}/growth-plan")
    public ApiResponse<GrowthPlanResponse> growthPlan(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        // 成长服务同时接收可信 userId 和面试 ID，负责归属校验、报告消费和缓存写入。
        return ApiResponse.success(growthPlanService.getOrCreatePlan(userId, id));
    }

    /** 校验面试属于认证用户后，按消息序号升序返回完整问答记录。 */
    @GetMapping("/{id}/messages")
    public ApiResponse<List<InterviewMessageResponse>> messages(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        // 应用服务先做会话归属查询，再读取仅按 interviewId 过滤的消息仓储。
        return ApiResponse.success(interviewService.listMessages(userId, id));
    }

    /**
     * 向当前 emitter 同步发送一个未命名 data 事件；I/O 失败只记录告警，不重抛也不重试。
     */
    private void sendEvent(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.warn("[InterviewController] SSE 发送失败: {}", e.getMessage());
        }
    }
}
