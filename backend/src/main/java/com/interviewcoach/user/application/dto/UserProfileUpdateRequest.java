package com.interviewcoach.user.application.dto;

import java.time.LocalDate;
import lombok.Data;

/**
 * 已认证用户提交的资料部分更新请求。
 *
 * <p>每个 {@code null} 字段都表示保留原值；非 {@code null} 字段会覆盖资料记录中的对应值。
 * 字符串空值不会被本 DTO 自动转换为 {@code null}。</p>
 */
@Data
public class UserProfileUpdateRequest {

    /**
     * 新展示昵称；{@code null} 表示不修改，空串会按当前实现写入。
     */
    private String nickname;

    /**
     * 新头像地址；{@code null} 表示不修改，空串会按当前实现写入。
     */
    private String avatar;

    /**
     * 新性别英文枚举码；{@code null} 表示不修改，当前服务按大小写敏感且不去空白的方式解析。
     */
    private String gender;

    /**
     * 新生日日期；{@code null} 表示不修改。
     */
    private LocalDate birthday;

    /**
     * 新个人简介；{@code null} 表示不修改，空串会按当前实现写入。
     */
    private String bio;
}
