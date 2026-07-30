package com.interviewcoach.resume.infrastructure.desensitize;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 简历文本脱敏器，在发送给外部模型前尽力移除直接身份信息。
 * 公司、学校和项目名称按当前产品约定保留。
 */
@Component
public class ResumeDesensitizer {

    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<!\\d)(?:\\d{15}|\\d{17}[\\dXx])(?!\\d)");
    private static final Pattern NAME_LINE = Pattern.compile(
            "(?m)(姓名\\s*[:：]?\\s*)[^\\r\\n]{2,20}");
    private static final Pattern FIRST_LINE_NAME = Pattern.compile(
            "\\A\\s*[\\p{IsHan}]{2,4}\\s*(?=\\r?\\n)");
    private static final Pattern BIRTHDAY_LINE = Pattern.compile(
            "(?im)((?:出生日期|出生年月|生日)\\s*[:：]?\\s*)[^\\r\\n]{2,30}");
    private static final Pattern ADDRESS_LINE = Pattern.compile(
            "(?im)((?:详细地址|家庭地址|现住址|现居地|住址|地址)\\s*[:：]?\\s*)[^\\r\\n]{2,100}");
    private static final Pattern SOCIAL_ACCOUNT_LINE = Pattern.compile(
            "(?im)((?:微信|wechat|qq)\\s*(?:号|账号)?\\s*[:：]?\\s*)[^\\r\\n]{2,50}");
    private static final Pattern PASSPORT_LINE = Pattern.compile(
            "(?im)((?:护照号|护照号码|护照)\\s*[:：]?\\s*)[a-z0-9]{5,20}");

    /**
     * 返回脱敏文本和命中的敏感类型。规则可能漏检，调用方按当前产品约定继续模型分析。
     */
    @AgentPermission(AgentType.RESUME_ANALYSIS)
    public DesensitizationResult desensitize(String text) {
        if (text == null || text.isBlank()) {
            return new DesensitizationResult(text, Set.of());
        }

        Set<String> maskedTypes = new LinkedHashSet<>();
        String result = mask(text, MOBILE, "[PHONE]", "PHONE", maskedTypes);
        result = mask(result, EMAIL, "[EMAIL]", "EMAIL", maskedTypes);
        result = mask(result, ID_CARD, "[ID_CARD]", "ID_CARD", maskedTypes);
        result = mask(result, NAME_LINE, "$1[NAME]", "NAME", maskedTypes);
        result = mask(result, FIRST_LINE_NAME, "[NAME]", "NAME", maskedTypes);
        result = mask(result, BIRTHDAY_LINE, "$1[BIRTHDAY]", "BIRTHDAY", maskedTypes);
        result = mask(result, ADDRESS_LINE, "$1[ADDRESS]", "ADDRESS", maskedTypes);
        result = mask(result, SOCIAL_ACCOUNT_LINE, "$1[CONTACT]", "SOCIAL_ACCOUNT", maskedTypes);
        result = mask(result, PASSPORT_LINE, "$1[PASSPORT]", "PASSPORT", maskedTypes);
        return new DesensitizationResult(result, Set.copyOf(maskedTypes));
    }

    private String mask(String text, Pattern pattern, String replacement,
                        String type, Set<String> maskedTypes) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        maskedTypes.add(type);
        return matcher.replaceAll(replacement);
    }

    public record DesensitizationResult(String text, Set<String> maskedTypes) {
    }
}
