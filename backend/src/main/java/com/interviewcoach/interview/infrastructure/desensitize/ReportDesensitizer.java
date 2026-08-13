package com.interviewcoach.interview.infrastructure.desensitize;

import com.interviewcoach.interview.application.dto.InterviewReportResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 面试报告的有限字段精确替换器。
 *
 * <p>它只遍历 strengths、weaknesses、conclusion 和 mdContent，并把调用方提供的完整公司名或
 * 姓名做字面替换；其他字段、变体、简称、电话、邮箱等内容原样保留。当前报告调用方传入
 * candidateName 为 {@code null}，因此实际只尝试替换公司名，不能据此保证报告已完整脱敏。</p>
 */
@Component
public class ReportDesensitizer {

    /** 命中完整公司名称时使用的固定替换文本。 */
    private static final String MASKED_COMPANY = "某公司";
    /** 命中完整候选人姓名时使用的固定替换文本。 */
    private static final String MASKED_NAME = "候选人";

    /**
     * 复制报告并对四个文本区域执行尽力精确替换。
     *
     * @param report      原始报告
     * @param companyName 公司名称（可为空）
     * @param candidateName 候选人姓名（可为空）
     * @return 保留结构字段、只替换有限文本的新报告对象
     */
    public InterviewReportResponse desensitize(InterviewReportResponse report,
                                               String companyName,
                                               String candidateName) {
        InterviewReportResponse result = new InterviewReportResponse();
        result.setInterviewId(report.getInterviewId());
        result.setOverallScore(report.getOverallScore());
        result.setGrade(report.getGrade());
        result.setPhases(report.getPhases());
        result.setDimensions(report.getDimensions());
        result.setKeyEvents(report.getKeyEvents());

        // 只遍历优势列表中的每个完整字符串，不处理阶段、维度或关键事件。
        result.setStrengths(maskList(report.getStrengths(), companyName, candidateName));
        // 只遍历薄弱点列表中的每个完整字符串。
        result.setWeaknesses(maskList(report.getWeaknesses(), companyName, candidateName));
        // 对结论和 Markdown 执行相同的字面替换，不做模式识别或二次扫描。
        result.setConclusion(maskText(report.getConclusion(), companyName, candidateName));
        result.setMdContent(maskText(report.getMdContent(), companyName, candidateName));

        return result;
    }

    /** 保持列表顺序和空值语义，对每个元素调用相同的文本替换。 */
    private List<String> maskList(List<String> items, String companyName, String candidateName) {
        if (items == null) {
            return null;
        }
        List<String> result = new ArrayList<>(items.size());
        for (String item : items) {
            result.add(maskText(item, companyName, candidateName));
        }
        return result;
    }

    /**
     * 对非空文本依次替换完整公司名和完整候选人姓名；空白输入原样返回。
     * 该顺序不识别同义写法、分隔写法或其他个人信息。
     */
    private String maskText(String text, String companyName, String candidateName) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;

        // 仅在调用方提供非空完整公司名时执行 Java 字面替换。
        if (companyName != null && !companyName.isBlank()) {
            result = result.replace(companyName, MASKED_COMPANY);
        }

        // 仅在调用方提供非空完整姓名时执行字面替换；当前报告服务传入 null。
        if (candidateName != null && !candidateName.isBlank()) {
            result = result.replace(candidateName, MASKED_NAME);
        }

        return result;
    }
}
