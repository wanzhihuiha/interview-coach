package com.interviewcoach.resume.domain.model;

import java.util.List;
import lombok.Data;

/**
 * 模型根据已确认简历事实生成的辅助分析，由 Agent 归一化后持久化，只用于面试选题和追问，不作为事实或评分结论。
 */
@Data
public class ResumeProfileAnalysisData {

    /** 模型识别的候选优势及其事实证据；没有内容时为空列表。 */
    private List<AnalysisItem> strengths;
    /** 面试中需要进一步核实的能力点及其事实证据；没有内容时为空列表。 */
    private List<AnalysisItem> verificationPoints;
    /** 对技能水平的推断及其证据；不得写回正式事实画像。 */
    private List<SkillAssessment> skillAssessments;

    /** 一条优势或待核实能力点，包含模型结论、事实引用和置信度。 */
    @Data
    public static class AnalysisItem {
        /** 供面试选题使用的辅助判断文本。 */
        private String content;
        /** 模型给出的事实引用；null 会归一化为空列表，当前代码不校验引用是否真实存在。 */
        private List<String> evidenceRefs;
        /** 模型置信度会被夹取到 0.0～1.0，缺失时补 0.0；当前代码不把它当作评分。 */
        private Double confidence;
    }

    /** 一条技能水平推断，供面试追问验证，不会覆盖正式画像中的原文明示水平。 */
    @Data
    public static class SkillAssessment {
        /** 被分析的技能名称。 */
        private String skill;
        /** 模型推断的能力等级文本，不属于已确认事实。 */
        private String inferredLevel;
        /** 模型给出的事实引用；null 会归一化为空列表，当前代码不校验引用是否真实存在。 */
        private List<String> evidenceRefs;
        /** 模型推断置信度会被夹取到 0.0～1.0，缺失时补 0.0；当前代码不把它当作评分。 */
        private Double confidence;
    }
}
