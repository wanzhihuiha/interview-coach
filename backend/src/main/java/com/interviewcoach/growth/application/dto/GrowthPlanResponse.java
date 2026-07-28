package com.interviewcoach.growth.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 成长方案响应：与前端 GrowthView 数据结构对齐。
 * 成长方案仅包含由评估报告推导出的学习材料（知识补全、学习路径、练习题），
 * 不包含评估报告原文。
 */
@Data
public class GrowthPlanResponse {

    private Long id;
    private String positionTitle;
    private List<LearningPathItem> learningPath;
    private List<Exercise> exercises;
    private List<KnowledgeGap> knowledgeGaps;
    private String mdContent;

    @Data
    public static class LearningPathItem {
        private String phase;
        private String duration;
        private String goal;
        private List<String> tasks;
        private List<Resource> resources;

        @Data
        public static class Resource {
            private String name;
            private String type;
            private String url;
        }
    }

    @Data
    public static class Exercise {
        private String title;
        private String content;
    }

    @Data
    public static class KnowledgeGap {
        private String topic;
        private String description;
        private String importance;
        private List<String> keywords;
    }
}
