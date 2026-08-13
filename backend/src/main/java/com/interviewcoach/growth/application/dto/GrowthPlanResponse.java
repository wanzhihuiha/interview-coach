package com.interviewcoach.growth.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 成长方案的 HTTP 响应载体。
 *
 * <p>成长应用服务从已持久化的结构化 JSON 还原本对象，或由教练组件在首次生成时组装，
 * 再经面试接口返回给前端展示和渲染 Markdown。对象只承载学习路径、练习题、知识缺口
 * 及其 Markdown 表达，不返回面试报告原文。</p>
 */
@Data
public class GrowthPlanResponse {

    /** 已持久化成长方案的主键；首次组装后由应用服务回填。 */
    private Long id;

    /** 面试创建时保存的岗位名称快照，用于前端标识本方案对应的目标岗位。 */
    private String positionTitle;

    /** 按薄弱点生成的阶段列表；本地组装路径按输入顺序排列。 */
    private List<LearningPathItem> learningPath;

    /** 与薄弱点逐项对应的练习题，供前端展示和用户自练。 */
    private List<Exercise> exercises;

    /** 从薄弱点转换出的知识补全条目，供用户确定优先补强内容。 */
    private List<KnowledgeGap> knowledgeGaps;

    /** 由上述结构化内容渲染出的完整 Markdown，亦作为实体的正文内容保存。 */
    private String mdContent;

    /**
     * 单个薄弱点对应的学习阶段，由教练组件组装后同时进入结构化 JSON 和 Markdown。
     */
    @Data
    public static class LearningPathItem {

        /** 阶段顺序及要攻克的薄弱点标题。 */
        private String phase;

        /** 当前模板给出的预计学习周期文本，不是系统计算的截止时间。 */
        private String duration;

        /** 完成本阶段后期望具备的面试回答或场景分析能力。 */
        private String goal;

        /** 本阶段建议依次完成的学习与实践任务文本。 */
        private List<String> tasks;

        /** 按岗位类别和薄弱点匹配到的固定外部资料；未匹配时为空列表。 */
        private List<Resource> resources;

        /**
         * 学习阶段引用的外部资料描述，供前端生成链接，也供 Markdown 渲染器输出推荐项。
         */
        @Data
        public static class Resource {

            /** 面向用户展示的资料名称。 */
            private String name;

            /** 资料类别文本；当前本地资源表统一使用“文档”。 */
            private String type;

            /** 固定资源表中配置的外部访问地址。 */
            private String url;
        }
    }

    /**
     * 针对一个薄弱点生成的练习内容，由教练组件写入响应并进入 Markdown 练习题章节。
     */
    @Data
    public static class Exercise {

        /** 包含序号、练习类型和薄弱点的展示标题。 */
        private String title;

        /** 要求用户完成的问答、方案、代码或场景演练说明。 */
        private String content;
    }

    /**
     * 从面试报告薄弱点转换出的补强主题，供前端和 Markdown 突出学习优先级。
     */
    @Data
    public static class KnowledgeGap {

        /** 面试报告中的原始薄弱点文本。 */
        private String topic;

        /** 按技术类或非技术类模板生成的补强方向说明。 */
        private String description;

        /** 当前本地规则给出的中文优先级文本，例如“高”或“中”。 */
        private String importance;

        /** 随岗位大类固定生成的学习检索与复习关键词。 */
        private List<String> keywords;
    }
}
