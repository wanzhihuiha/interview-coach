package com.interviewcoach.interview.domain.service;

import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 题库工具使用的字符 n-gram Jaccard 相似度检查器。
 *
 * <p>保存临时题前，工具先做精确正文查询，再用本组件比较现有题。该算法只做文本归一化、
 * 互相包含判断和字符片段集合相似度，不提供语义等价保证，也不调用向量或外部模型服务。</p>
 */
@Component
public class QuestionSimilarityChecker {

    /** 早期保留的二元片段默认常量；当前构造器直接使用配置默认值 2，本常量没有消费者。 */
    private static final int DEFAULT_N_GRAM = 2;

    /** 判定重复所需的 Jaccard 阈值；当前配置缺失时为 0.85。 */
    private final double duplicateThreshold;
    /** 实际使用的字符片段长度，构造时至少收敛为 1。 */
    private final int nGram;

    /**
     * 从配置读取重复阈值和片段长度。
     *
     * <p>源码默认阈值 0.85 与旧设计文档中的 0.92 冲突，当前精确依据缺失；阈值越低越容易
     * 把不同题判重，越高越容易保留近似题。n-gram 越大越强调连续长片段，越小越宽松。</p>
     */
    public QuestionSimilarityChecker(
            @Value("${interview.question.duplicate-threshold:0.85}") double duplicateThreshold,
            @Value("${interview.question.duplicate-ngram:2}") int nGram) {
        this.duplicateThreshold = duplicateThreshold;
        this.nGram = Math.max(1, nGram);
    }

    /**
     * 判断两段题目是否重复。
     * 空值、归一化后空串返回 false；完全相等或任一包含另一方直接返回 true，否则比较 Jaccard 阈值。
     */
    public boolean isDuplicate(String source, String target) {
        if (source == null || target == null) {
            return false;
        }
        String normalizedSource = normalize(source);
        String normalizedTarget = normalize(target);
        if (normalizedSource.isEmpty() || normalizedTarget.isEmpty()) {
            return false;
        }
        if (normalizedSource.equals(normalizedTarget)) {
            return true;
        }
        // 互相包含时直接判定重复，不再受配置阈值影响。
        if (normalizedSource.contains(normalizedTarget) || normalizedTarget.contains(normalizedSource)) {
            return true;
        }
        return calculateJaccardSimilarity(normalizedSource, normalizedTarget) >= duplicateThreshold;
    }

    /**
     * 计算两段归一化文本的字符 n-gram Jaccard 相似度，返回 0.0～1.0；空输入返回 0。
     * 本方法不应用重复阈值，也不执行互相包含的提前判重。
     */
    public double calculateSimilarity(String source, String target) {
        if (source == null || target == null) {
            return 0.0;
        }
        String normalizedSource = normalize(source);
        String normalizedTarget = normalize(target);
        if (normalizedSource.isEmpty() || normalizedTarget.isEmpty()) {
            return 0.0;
        }
        return calculateJaccardSimilarity(normalizedSource, normalizedTarget);
    }

    /** 用交集片段数除以并集片段数；任一片段集合为空时返回 0。 */
    private double calculateJaccardSimilarity(String source, String target) {
        Set<String> sourceGrams = buildNGrams(source);
        Set<String> targetGrams = buildNGrams(target);
        if (sourceGrams.isEmpty() || targetGrams.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(sourceGrams);
        intersection.retainAll(targetGrams);

        Set<String> union = new HashSet<>(sourceGrams);
        union.addAll(targetGrams);

        return (double) intersection.size() / union.size();
    }

    /**
     * 按 UTF-16 子串构造去重后的连续字符片段集合；文本短于 nGram 时返回空集合。
     */
    private Set<String> buildNGrams(String text) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i <= text.length() - nGram; i++) {
            grams.add(text.substring(i, i + nGram));
        }
        return grams;
    }

    /** 去除两端空白、转小写，并移除除中文、ASCII 字母和数字外的字符。 */
    private String normalize(String content) {
        return content.trim()
                .toLowerCase()
                .replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "")
                .replaceAll("\\s+", "");
    }
}
