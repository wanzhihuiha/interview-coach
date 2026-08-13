package com.interviewcoach.user.application.service;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 为当前模拟微信登录流程派生本地 OpenID 的服务。
 *
 * <p>认证服务调用本组件把非空 code 转成可重复查询的模拟标识；本组件不联网、不调用微信 API，
 * 也不校验 code 的签发方、有效期或调用者微信身份。</p>
 */
@Service
public class WechatService {

    /**
     * 根据 code 在本地确定性派生固定格式的模拟 OpenID。
     *
     * <p>同一运行环境字符集下，相同 code 会得到相同结果：方法使用进程默认字符集转换字节，
     * 基于名称 UUID 取前 16 个十六进制字符并加上 {@code mock_openid_} 前缀。</p>
     *
     * @param code 用于本地派生的非空字符串，不会发送给微信
     * @return 仅供当前模拟登录查询和建号使用的 OpenID
     */
    public String codeToOpenid(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("微信授权码不能为空");
        }
        // 只在本地派生模拟标识；该结果不代表 code 已经通过微信服务验证。
        return "mock_openid_" + UUID.nameUUIDFromBytes(code.getBytes()).toString().replace("-", "").substring(0, 16);
    }
}
