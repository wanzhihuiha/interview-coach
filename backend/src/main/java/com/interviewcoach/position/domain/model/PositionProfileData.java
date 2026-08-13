package com.interviewcoach.position.domain.model;

import java.util.List;
import lombok.Data;

/**
 * LLM 岗位分析结果以及用户确认后的正式画像数据模型。
 * 模型输出先作为当前任务候选跨越模型与应用边界，确认后序列化到正式画像，并供详情和面试流程读取。
 */
@Data
public class PositionProfileData {

    /** 从 JD 提取或推断的岗位基础信息；当前模型校验要求对象存在，但内部字段允许为空。 */
    private BasicInfo basicInfo;
    /** 模型判定为岗位必需的技能项；允许为空列表，但与其他考察内容不能全部为空。 */
    private List<SkillItem> requiredSkills;
    /** 模型判定为加分项的技能集合；允许为空列表，元素必须包含技能、重要性和深度。 */
    private List<SkillItem> preferredSkills;
    /** 面试应继续追问的方向集合，后续面试编排读取方向、优先级、深度与示例问题。 */
    private List<ProbingDirection> probingDirections;
    /** 面试整体关注维度列表；当前校验要求至少一项且每项非空白。 */
    private List<String> interviewFocus;
    /**
     * 模型对画像完整度的置信值；当前契约校验为有限数且位于 0.0 至 1.0，精确区间依据未在代码或可靠文档中记录。
     */
    private Double confidenceLevel;

    /** JD 中岗位名称、公司、地点、职级和薪资的基础信息分组，供画像展示与面试上下文使用。 */
    @Data
    public static class BasicInfo {
        /** 从 JD 提取或推断的岗位名称；模型未给出时可以为空。 */
        private String title;
        /** 从 JD 提取的公司名称；JD 未提供时可以为空。 */
        private String company;
        /** 从 JD 提取的工作地点；JD 未提供时可以为空。 */
        private String location;
        /** 模型输出的岗位职级，用于确认时更新岗位职级；未识别时可以为空。 */
        private String level;
        /** 从 JD 提取的薪资范围文本；JD 未提供时可以为空。 */
        private String salaryRange;
    }

    /** 单项岗位技能要求，区分技能名称、必需或加分属性以及模型给出的掌握深度。 */
    @Data
    public static class SkillItem {
        /** 技能或能力名称；候选画像校验要求非空白。 */
        private String skill;
        /** 模型给出的重要性文本，当前提示词使用“必须”或“加分”，校验仅要求非空白。 */
        private String importance;
        /** 模型给出的深度等级或区间文本；当前提示词使用 L1-L5，精确分级依据未在代码或可靠文档中记录。 */
        private String depth;
    }

    /** 面试追问方向，描述要考察的主题、先后优先级、深度范围和可选示例问题。 */
    @Data
    public static class ProbingDirection {
        /** 需要继续考察的主题名称；候选画像校验要求非空白。 */
        private String direction;
        /**
         * 当前模型契约使用 1 至 5 的优先级且数字越小越优先；该范围会被校验，精确分级依据未在可靠文档中记录。
         */
        private Integer priority;
        /** 追问深度范围文本；提示词示例使用 L3-L5，校验仅要求非空白。 */
        private String depthRange;
        /**
         * 供后续面试编排参考的问题列表；提示词请求每个方向 2 至 3 个，当前代码只校验列表非空且元素非空白，数量依据未记录。
         */
        private List<String> sampleQuestions;
    }
}
