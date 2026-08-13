package com.interviewcoach.position.application.dto;

import com.interviewcoach.position.domain.model.PositionProfileData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 岗位候选画像确认请求体，由个人岗位所有者或公共岗位管理入口从 HTTP 层提交。
 * 应用服务把任务代次与确认后的画像一并交给状态事务校验，成功后画像成为面试流程读取的正式版本。
 */
@Data
public class ConfirmPositionRequest {

    /**
     * 当前页面所确认的候选任务 ID；状态事务会据此拒绝已经被替换或不再待确认的旧任务。
     */
    @NotNull(message = "当前任务 ID 不能为空")
    private Long taskId;

    /**
     * 调用方最终确认的完整岗位画像；通过校验后序列化到正式画像记录，后续详情和面试流程读取该版本。
     */
    @NotNull(message = "画像数据不能为空")
    @Valid
    private PositionProfileData profile;
}
