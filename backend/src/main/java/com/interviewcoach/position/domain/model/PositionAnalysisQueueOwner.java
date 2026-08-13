package com.interviewcoach.position.domain.model;

/**
 * 生成岗位公平队列的服务端参与者标识，禁止使用请求参数直接构造队列归属。
 */
public final class PositionAnalysisQueueOwner {

    /** 公共岗位共用的 Redis 队列参与者标识，由服务端固定生成。 */
    public static final String PUBLIC = "PUBLIC";

    /** 个人岗位队列参与者标识前缀，后接规范十进制正数用户 ID。 */
    private static final String USER_PREFIX = "USER:";

    private PositionAnalysisQueueOwner() {
    }

    /**
     * 根据可信服务端用户 ID 构造个人队列参与者标识，禁止用请求字符串替代该入口。
     *
     * @param userId 个人岗位所属用户的正数 ID
     * @return 形如 {@code USER:123} 的 Redis 队列归属
     * @throws IllegalArgumentException 用户 ID 为空或不是正数时抛出
     */
    public static String forUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 ID 必须为正数");
        }
        return USER_PREFIX + userId;
    }

    /**
     * 校验队列归属是否为固定 PUBLIC，或为可规范往返的正数用户标识。
     *
     * @param value 待校验的数据库或 Redis 队列归属
     * @return 只有服务端支持的规范格式才返回 {@code true}
     */
    public static boolean isValid(String value) {
        if (PUBLIC.equals(value)) {
            return true;
        }
        if (value == null || !value.startsWith(USER_PREFIX)) {
            return false;
        }
        try {
            long userId = Long.parseLong(value.substring(USER_PREFIX.length()));
            return userId > 0 && forUser(userId).equals(value);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
