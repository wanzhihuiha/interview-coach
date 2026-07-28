package com.interviewcoach.user.application.service;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 微信登录服务（当前为模拟实现，生产环境需调用微信服务端 API）。
 */
@Service
public class WechatService {

    /**
     * 通过微信授权码换取 OpenID。
     * <p>
     * 当前实现根据 code 生成固定格式的模拟 openid，生产环境应调用微信
     * <code>jscode2session</code> 接口。
     *
     * @param code 微信授权码
     * @return 微信 openid
     */
    public String codeToOpenid(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("微信授权码不能为空");
        }
        // TODO: 生产环境调用微信 jscode2session 接口
        return "mock_openid_" + UUID.nameUUIDFromBytes(code.getBytes()).toString().replace("-", "").substring(0, 16);
    }
}
