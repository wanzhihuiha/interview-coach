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
 * 当前正则的完整覆盖、误判/漏判和调参依据均缺失，命中结果不能证明文本已完全脱敏。
 */
@Component
public class ResumeDesensitizer {

    /** 中国大陆手机号的当前正则；覆盖范围和误判、漏判依据缺失。 */
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 常见邮箱格式的当前正则；不保证覆盖所有合法地址。 */
    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    /** 15 位或 18 位中国大陆身份证号的当前正则，不校验校验位。 */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<!\\d)(?:\\d{15}|\\d{17}[\\dXx])(?!\\d)");
    /** 带“姓名”标签的单行姓名替换规则；2～20 字符范围依据缺失。 */
    private static final Pattern NAME_LINE = Pattern.compile(
            "(?m)(姓名\\s*[:：]?\\s*)[^\\r\\n]{2,20}");
    /** 文档首行 2～4 个汉字姓名的启发式替换规则，可能误判普通标题。 */
    private static final Pattern FIRST_LINE_NAME = Pattern.compile(
            "\\A\\s*[\\p{IsHan}]{2,4}\\s*(?=\\r?\\n)");
    /** 带出生日期类标签的单行值替换规则；长度范围依据缺失。 */
    private static final Pattern BIRTHDAY_LINE = Pattern.compile(
            "(?im)((?:出生日期|出生年月|生日)\\s*[:：]?\\s*)[^\\r\\n]{2,30}");
    /** 带地址类标签的单行值替换规则；长度范围依据缺失。 */
    private static final Pattern ADDRESS_LINE = Pattern.compile(
            "(?im)((?:详细地址|家庭地址|现住址|现居地|住址|地址)\\s*[:：]?\\s*)[^\\r\\n]{2,100}");
    /** 带微信或 QQ 标签的单行账号替换规则；长度范围依据缺失。 */
    private static final Pattern SOCIAL_ACCOUNT_LINE = Pattern.compile(
            "(?im)((?:微信|wechat|qq)\\s*(?:号|账号)?\\s*[:：]?\\s*)[^\\r\\n]{2,50}");
    /** 带护照标签的 5～20 位字母数字替换规则；长度范围依据缺失。 */
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
        // 按稳定顺序逐类替换并累计命中类型；前序替换结果作为后续规则输入。
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

    /** 对一种敏感模式执行全量替换，并在至少命中一次时记录类型。 */
    private String mask(String text, Pattern pattern, String replacement,
                        String type, Set<String> maskedTypes) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        maskedTypes.add(type);
        return matcher.replaceAll(replacement);
    }

    /**
     * 尽力脱敏结果；规则漏检不会阻止调用方继续把文本发送给模型。
     *
     * @param text 按当前规则替换后的文本，输入为空时原样返回
     * @param maskedTypes 实际命中的敏感类型只读集合，未命中时为空集合
     */
    public record DesensitizationResult(String text, Set<String> maskedTypes) {
    }
}
