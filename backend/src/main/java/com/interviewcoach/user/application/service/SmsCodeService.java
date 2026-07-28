package com.interviewcoach.user.application.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 短信验证码服务（当前为模拟实现，生产环境需接入真实短信网关）。
 */
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    private static final String SMS_CODE_PREFIX = "sms:code:";
    private static final long SMS_CODE_EXPIRE_MINUTES = 5;
    private static final long SMS_CODE_SEND_INTERVAL_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;

    /**
     * 发送短信验证码。
     * <p>
     * 当前实现生成 6 位数字验证码并写入 Redis，同时在日志中输出验证码内容，便于开发调试。
     * 生产环境应调用真实短信网关发送。
     *
     * @param phone 手机号
     * @return 验证码（仅用于调试，生产环境不应返回）
     */
    public String sendCode(String phone) {
        String intervalKey = SMS_CODE_PREFIX + "interval:" + phone;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(intervalKey))) {
            throw new IllegalStateException("短信发送过于频繁，请稍后再试");
        }

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        String codeKey = SMS_CODE_PREFIX + phone;
        redisTemplate.opsForValue().set(codeKey, code, SMS_CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(intervalKey, "1", SMS_CODE_SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // TODO: 生产环境接入短信网关，此处仅打印日志
        System.out.printf("[SMS-MOCK] phone=%s, code=%s%n", phone, code);
        return code;
    }

    /**
     * 校验短信验证码。
     *
     * @param phone 手机号
     * @param code  验证码
     * @return 校验是否通过
     */
    public boolean verifyCode(String phone, String code) {
        String codeKey = SMS_CODE_PREFIX + phone;
        String storedCode = redisTemplate.opsForValue().get(codeKey);
        if (storedCode == null) {
            return false;
        }
        if (!storedCode.equals(code)) {
            return false;
        }
        redisTemplate.delete(codeKey);
        return true;
    }
}
