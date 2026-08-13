package com.interviewcoach.user.application.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的模拟短信验证码服务。
 *
 * <p>用户接口调用本服务生成或校验一次性验证码。当前实现不连接短信网关，而是把手机号和验证码明文
 * 写入标准输出，并把验证码原样返回给 HTTP 层；因此它只能表示本地 Mock 流程，不能证明短信已送达。</p>
 */
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    /**
     * 验证码与发送频控 Redis Key 的共同前缀；当前 Key 后缀直接包含手机号。
     */
    private static final String SMS_CODE_PREFIX = "sms:code:";

    /**
     * 验证码 Redis Key 的有效期，单位为分钟；用户模块设计明确当前值为 5 分钟。
     * 调大将延长旧验证码可匹配时间，调小会缩短用户可输入时间。
     */
    private static final long SMS_CODE_EXPIRE_MINUTES = 5;

    /**
     * 同一手机号再次生成验证码前的频控时间，单位为秒；用户模块设计明确当前值为 60 秒。
     * 调大将降低发送频率，调小会允许更快重复生成。
     */
    private static final long SMS_CODE_SEND_INTERVAL_SECONDS = 60;

    /**
     * 负责验证码、频控标记的 Redis 读取、限时写入和删除。
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 为手机号生成模拟验证码并建立 Redis 频控标记。
     *
     * <p>当前固定生成 6 位数字，精确位数的项目依据缺失。验证码 Key 保存 5 分钟，频控 Key 保存 60 秒；
     * 两次 Redis 写入不是一个原子操作。频控命中时抛出 {@link IllegalStateException}，不会使用当前未接入的
     * {@link UserErrorCode#SMS_SEND_TOO_FREQUENT}。成功后会向标准输出和返回值暴露手机号及验证码明文。</p>
     *
     * @param phone 用于构造 Redis Key 和 Mock 输出的手机号
     * @return 当前 Mock 接口直接返回的 6 位明文验证码
     */
    public String sendCode(String phone) {
        String intervalKey = SMS_CODE_PREFIX + "interval:" + phone;
        // 先检查该手机号的频控 Key；存在时不生成新验证码。
        if (Boolean.TRUE.equals(redisTemplate.hasKey(intervalKey))) {
            throw new IllegalStateException("短信发送过于频繁，请稍后再试");
        }

        // 当前固定生成包含前导零的 6 位数字验证码；精确位数依据缺失。
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        String codeKey = SMS_CODE_PREFIX + phone;
        // 将明文验证码保存 5 分钟，供后续登录、注册或绑定流程读取。
        redisTemplate.opsForValue().set(codeKey, code, SMS_CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        // 独立写入 60 秒频控 Key；该写入与验证码保存不具备原子性。
        redisTemplate.opsForValue().set(intervalKey, "1", SMS_CODE_SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // 当前 Mock 行为把手机号和验证码明文写到标准输出，不会调用任何短信网关。
        System.out.printf("[SMS-MOCK] phone=%s, code=%s%n", phone, code);
        return code;
    }

    /**
     * 从 Redis 读取并校验手机号对应的一次性验证码。
     *
     * <p>Key 不存在或明文不匹配时返回 {@code false} 且不删除任何 Key；匹配时请求删除验证码 Key 后返回
     * {@code true}，不会删除仍在计时的频控 Key，当前也不检查 Redis 删除操作的返回值。</p>
     *
     * @param phone 用于定位验证码 Redis Key 的手机号
     * @param code 用户回传的明文验证码
     * @return 读取到的验证码与输入完全相同时为 {@code true}，否则为 {@code false}
     */
    public boolean verifyCode(String phone, String code) {
        String codeKey = SMS_CODE_PREFIX + phone;
        // 从 Redis 读取仍在有效期内的明文验证码，Key 过期或不存在时直接失败。
        String storedCode = redisTemplate.opsForValue().get(codeKey);
        if (storedCode == null) {
            return false;
        }
        if (!storedCode.equals(code)) {
            return false;
        }
        // 匹配成功后只请求删除验证码 Key；正常删除后不能再次通过，但当前不检查删除结果，频控 Key 保持原 TTL。
        redisTemplate.delete(codeKey);
        return true;
    }
}
