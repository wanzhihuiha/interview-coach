package com.interviewcoach.common.llm;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 第一阶段本地确定性检测器，覆盖明显指令挟制、高风险动作、一次性编码指令和 Unicode 控制字符。
 *
 * <p>规则命中只是风险信号，不能代替 DATA_ONLY 隔离、输出契约或服务端状态控制。</p>
 */
@Component
@Qualifier(PromptRiskDetectorChain.DELEGATE_QUALIFIER)
public class LocalPromptRiskDetector implements PromptRiskDetector {

    private static final int MAX_SIGNALS_PER_BLOCK = 20;
    private static final int MAX_ENCODED_CANDIDATE_CHARACTERS = 2_048;
    private static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE | Pattern.MULTILINE | Pattern.DOTALL;
    private static final String INSTRUCTION_BOUNDARY =
            "(?:^|[\\r\\n\\\"'“”‘’\\[（(:：,，。！？.!?;；])(?:[\\s>*#-]*)";
    private static final String DIRECTIVE_PREFIX =
            "(?:(?:please|you\\s+must|must|请|现在|立即|务必|必须)\\s*)";

    private static final Pattern BASE64_CANDIDATE = Pattern.compile(
            "(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_-]{16,"
                    + MAX_ENCODED_CANDIDATE_CHARACTERS
                    + "}={0,2}(?![A-Za-z0-9+/_=-])");
    private static final Pattern HEX_CANDIDATE = Pattern.compile(
            "(?i)(?<![0-9a-f])[0-9a-f]{32,"
                    + MAX_ENCODED_CANDIDATE_CHARACTERS
                    + "}(?![0-9a-f])");

    private static final List<PatternRule> VISIBLE_INSTRUCTION_RULES = List.of(
            new PatternRule(
                    PromptRiskSignal.RiskType.INSTRUCTION_OVERRIDE,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY
                                    + "(?:(?:please|请|现在|立即|务必|必须)\\s*)?"
                                    + "(?:ignore|disregard|override|bypass|forget|忽略|无视|覆盖|绕过|忘记)"
                                    + ".{0,48}(?:previous|prior|above|system|developer|之前|先前|以上|系统|开发者)"
                                    + ".{0,24}(?:instructions?|rules?|prompts?|messages?|指令|规则|提示词?|消息)",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH),
            new PatternRule(
                    PromptRiskSignal.RiskType.ROLE_IMPERSONATION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY
                                    + "(?:(?:system|developer|assistant)\\s*(?:message)?"
                                    + "|系统消息|开发者消息|助手消息)\\s*[:：]",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH),
            new PatternRule(
                    PromptRiskSignal.RiskType.ROLE_IMPERSONATION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY
                                    + "(?:请\\s*)?(?:你|模型|助手)(?:现在)?"
                                    + "(?:扮演|作为|切换为|变成).{0,24}(?:系统|开发者|管理员)",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.MEDIUM),
            new PatternRule(
                    PromptRiskSignal.RiskType.ROLE_IMPERSONATION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY
                                    + "(?:(?:you|assistant|model)\\s+"
                                    + "(?:(?:are|become)\\s+(?:now\\s+)?|(?:must|should)\\s+(?:be|act\\s+as)\\s+)"
                                    + "(?:the\\s+)?(?:system|developer|administrator|admin)"
                                    + "|(?:你|模型|助手)(?:现在|从现在起)?(?:是|成为|充当)"
                                    + "(?:系统|开发者|管理员))",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH),
            new PatternRule(
                    PromptRiskSignal.RiskType.SENSITIVE_DATA_EXFILTRATION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY
                                    + "(?:(?:please|you\\s+must|must|请|现在|立即|务必|必须|把|将)\\s*)?"
                                    + "(?:(?:send|post|upload|email|reveal|print|return|read|retrieve|access"
                                    + "|发送|上传|邮件发送|泄露|展示|输出|读取|获取)"
                                    + ".{0,64}(?:system\\s*prompt|api\\s*key|access\\s*token|secret|cookie"
                                    + "|系统提示词|密钥|令牌|密码|私钥)"
                                    + "|(?:system\\s*prompt|api\\s*key|access\\s*token|secret|cookie"
                                    + "|系统提示词|密钥|令牌|密码|私钥).{0,64}"
                                    + "(?:send|post|upload|email|reveal|print|return|read|retrieve|access"
                                    + "|发送|上传|邮件发送|外发|泄露|展示|输出|读取|获取))",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH),
            new PatternRule(
                    PromptRiskSignal.RiskType.TOOL_OR_HIGH_RISK_ACTION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY + DIRECTIVE_PREFIX
                                    + "(?:call|invoke|execute|run|use|调用|执行|运行|使用)"
                                    + ".{0,40}(?:tool|function|shell|command|cmd|powershell|bash|curl|wget"
                                    + "|rm\\s+-rf|drop\\s+(?:database|table)|truncate\\s+table"
                                    + "|工具|函数|命令|脚本)",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH),
            new PatternRule(
                    PromptRiskSignal.RiskType.TOOL_OR_HIGH_RISK_ACTION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY
                                    + "(?:rm\\s+-rf(?:\\s|$)|drop\\s+(?:database|table)\\b"
                                    + "|truncate\\s+table\\b|powershell(?:\\.exe)?\\s+-enc(?:odedcommand)?\\b)",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH),
            new PatternRule(
                    PromptRiskSignal.RiskType.TOOL_OR_HIGH_RISK_ACTION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY + DIRECTIVE_PREFIX
                                    + "(?:visit|open|navigate|访问|打开|跳转到)"
                                    + ".{0,32}https?://",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH),
            new PatternRule(
                    PromptRiskSignal.RiskType.TOOL_OR_HIGH_RISK_ACTION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY + DIRECTIVE_PREFIX
                                    + "(?:delete|drop|truncate|remove|change|modify|grant|删除|清空|修改|授予)"
                                    + ".{0,48}(?:database|table|permission|configuration|config|system"
                                    + "|data|数据库|数据|数据表|表结构|权限|配置|系统)",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH),
            new PatternRule(
                    PromptRiskSignal.RiskType.TOOL_OR_HIGH_RISK_ACTION,
                    Pattern.compile(
                            INSTRUCTION_BOUNDARY + DIRECTIVE_PREFIX
                                    + "(?:(?:把|将).{0,32})?"
                                    + "(?:save|store|remember|persist|保存|写入|记住|设为)"
                                    + ".{0,48}(?:system\\s*rules?|global\\s*rules?|long[-\\s]*term\\s*memory"
                                    + "|系统规则|全局规则|永久规则|长期记忆)",
                            PATTERN_FLAGS),
                    PromptRiskSignal.Confidence.HIGH));

    @Override
    public List<PromptRiskSignal> detect(List<LlmDataBlock> dataBlocks) {
        List<PromptRiskSignal> signals = new ArrayList<>();
        for (LlmDataBlock dataBlock : dataBlocks) {
            detectUnicodeControls(dataBlock, signals);
            detectVisibleInstructions(dataBlock, signals);
            detectEncodedInstructions(dataBlock, signals);
        }
        return List.copyOf(signals);
    }

    private void detectUnicodeControls(LlmDataBlock dataBlock, List<PromptRiskSignal> signals) {
        String text = dataBlock.text();
        int utf16Offset = 0;
        int characterOffset = 0;
        int blockSignalCount = countSignals(signals, dataBlock.blockId());
        while (utf16Offset < text.length() && blockSignalCount < MAX_SIGNALS_PER_BLOCK) {
            int codePoint = text.codePointAt(utf16Offset);
            PromptRiskSignal.RiskType riskType = unicodeRiskType(codePoint);
            if (riskType != null) {
                signals.add(new PromptRiskSignal(
                        dataBlock.blockId(),
                        riskType,
                        characterOffset,
                        characterOffset + 1,
                        PromptRiskSignal.Confidence.HIGH,
                        PromptRiskSignal.SuggestedDisposition.QUARANTINED));
                blockSignalCount++;
            }
            utf16Offset += Character.charCount(codePoint);
            characterOffset++;
        }
    }

    /**
     * 只对特征明确且有长度上限的 Base64/十六进制片段解码一次；解码结果不递归处理，也不记录原文。
     */
    private void detectEncodedInstructions(
            LlmDataBlock dataBlock, List<PromptRiskSignal> signals) {
        int blockSignalCount = countSignals(signals, dataBlock.blockId());
        if (blockSignalCount >= MAX_SIGNALS_PER_BLOCK) {
            return;
        }
        blockSignalCount = detectEncodedCandidates(
                dataBlock, signals, BASE64_CANDIDATE, this::decodeBase64, blockSignalCount);
        if (blockSignalCount < MAX_SIGNALS_PER_BLOCK) {
            detectEncodedCandidates(
                    dataBlock, signals, HEX_CANDIDATE, this::decodeHex, blockSignalCount);
        }
    }

    private int detectEncodedCandidates(
            LlmDataBlock dataBlock,
            List<PromptRiskSignal> signals,
            Pattern candidatePattern,
            EncodedTextDecoder decoder,
            int blockSignalCount) {
        Matcher matcher = candidatePattern.matcher(dataBlock.text());
        while (matcher.find() && blockSignalCount < MAX_SIGNALS_PER_BLOCK) {
            String decoded = decoder.decode(matcher.group());
            if (decoded != null && containsVisibleInstruction(decoded)) {
                signals.add(new PromptRiskSignal(
                        dataBlock.blockId(),
                        PromptRiskSignal.RiskType.ENCODED_INSTRUCTION,
                        dataBlock.text().codePointCount(0, matcher.start()),
                        dataBlock.text().codePointCount(0, matcher.end()),
                        PromptRiskSignal.Confidence.HIGH,
                        PromptRiskSignal.SuggestedDisposition.QUARANTINED));
                blockSignalCount++;
            }
        }
        return blockSignalCount;
    }

    private boolean containsVisibleInstruction(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return VISIBLE_INSTRUCTION_RULES.stream()
                .anyMatch(rule -> rule.pattern().matcher(normalized).find());
    }

    private String decodeBase64(String encoded) {
        if (encoded.length() % 4 == 1) {
            return null;
        }
        try {
            Base64.Decoder decoder = encoded.indexOf('-') >= 0 || encoded.indexOf('_') >= 0
                    ? Base64.getUrlDecoder() : Base64.getDecoder();
            return decodeUtf8(decoder.decode(encoded));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String decodeHex(String encoded) {
        if ((encoded.length() & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[encoded.length() / 2];
        for (int index = 0; index < encoded.length(); index += 2) {
            int high = Character.digit(encoded.charAt(index), 16);
            int low = Character.digit(encoded.charAt(index + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[index / 2] = (byte) ((high << 4) | low);
        }
        return decodeUtf8(bytes);
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private void detectVisibleInstructions(LlmDataBlock dataBlock, List<PromptRiskSignal> signals) {
        String text = dataBlock.text();
        int blockSignalCount = countSignals(signals, dataBlock.blockId());
        for (PatternRule rule : VISIBLE_INSTRUCTION_RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find() && blockSignalCount < MAX_SIGNALS_PER_BLOCK) {
                signals.add(signalForMatch(dataBlock, rule, matcher.start(), matcher.end()));
                blockSignalCount++;
            }
        }

        if (blockSignalCount >= MAX_SIGNALS_PER_BLOCK) {
            return;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (normalized.equals(text)) {
            return;
        }
        for (PatternRule rule : VISIBLE_INSTRUCTION_RULES) {
            if (rule.pattern().matcher(normalized).find() && !rule.pattern().matcher(text).find()) {
                signals.add(new PromptRiskSignal(
                        dataBlock.blockId(),
                        PromptRiskSignal.RiskType.COMPATIBILITY_OBFUSCATION,
                        0,
                        dataBlock.characterCount(),
                        PromptRiskSignal.Confidence.MEDIUM,
                        PromptRiskSignal.SuggestedDisposition.SUSPICIOUS));
                return;
            }
        }
    }

    private PromptRiskSignal signalForMatch(
            LlmDataBlock dataBlock, PatternRule rule, int startUtf16, int endUtf16) {
        String text = dataBlock.text();
        int startCharacter = text.codePointCount(0, startUtf16);
        int endCharacter = text.codePointCount(0, endUtf16);
        return new PromptRiskSignal(
                dataBlock.blockId(),
                rule.riskType(),
                startCharacter,
                endCharacter,
                rule.confidence(),
                PromptRiskSignal.SuggestedDisposition.SUSPICIOUS);
    }

    private PromptRiskSignal.RiskType unicodeRiskType(int codePoint) {
        if ((codePoint >= 0 && codePoint < 0x20
                && codePoint != '\t' && codePoint != '\n' && codePoint != '\r')
                || (codePoint >= 0x7F && codePoint <= 0x9F)) {
            return PromptRiskSignal.RiskType.CONTROL_CHARACTER;
        }
        if (codePoint == 0x00AD || codePoint == 0x034F || codePoint == 0x180E
                || codePoint == 0x200B
                || codePoint == 0x2060 || codePoint == 0xFEFF) {
            return PromptRiskSignal.RiskType.ZERO_WIDTH_CHARACTER;
        }
        if (codePoint == 0x061C || codePoint == 0x200E || codePoint == 0x200F
                || (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
            return PromptRiskSignal.RiskType.BIDIRECTIONAL_CONTROL;
        }
        return null;
    }

    private int countSignals(List<PromptRiskSignal> signals, String blockId) {
        return (int) signals.stream().filter(signal -> signal.blockId().equals(blockId)).count();
    }

    private record PatternRule(
            PromptRiskSignal.RiskType riskType,
            Pattern pattern,
            PromptRiskSignal.Confidence confidence) {
    }

    @FunctionalInterface
    private interface EncodedTextDecoder {

        String decode(String encoded);
    }
}
