package com.interviewcoach.interview.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 创建面试的 HTTP 请求体。
 *
 * <p>接口层把当前登录用户 ID 与本请求一并交给应用服务；应用服务会整理环节顺序，
 * 状态服务再复核简历、岗位及其画像是否属于本次可创建范围。</p>
 */
@Data
public class CreateInterviewRequest {

    /** 当前登录用户选择的简历 ID，创建事务会按用户归属加锁并读取正式画像。 */
    private Long resumeId;

    /** 用户选择的岗位 ID，可以指向本人私有岗位或当前可访问的公共岗位。 */
    private Long positionId;

    /**
     * 用户提交的环节枚举名；应用服务会去除重复项、按固定顺序排序并保证末尾包含结束环节。
     * 空列表、空白元素或未知枚举名会在创建流程中被拒绝。
     */
    private List<String> selectedPhases;
}
