package com.interviewcoach.position.domain.model;

/**
 * 生成岗位公平队列的服务端参与者标识，禁止使用请求参数直接构造队列归属。
 */
public final class PositionAnalysisQueueOwner {

    public static final String PUBLIC = "PUBLIC";
    private static final String USER_PREFIX = "USER:";

    private PositionAnalysisQueueOwner() {
    }

    public static String forUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 ID 必须为正数");
        }
        return USER_PREFIX + userId;
    }

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
