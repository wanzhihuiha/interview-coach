package com.interviewcoach.interview.domain.service;

import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 题目文本相似度检查器。
 *
 * <p>MVP 阶段使用基于字符 n-gram 的 Jaccard 相似度，无需引入外部向量库或 Embedding 服务。
 * 当题库规模扩大、需要语义级去重时，可替换为基于向量的余弦相似度实现。
 */
@Component
public class QuestionSimilarityChecker {

    private static final int DEFAULT_N_GRAM = 2;

    private final double duplicateThreshold;
    private final int nGram;

    public QuestionSimilarityChecker(
            @Value("${interview.question.duplicate-threshold:0.85}") double duplicateThreshold,
            @Value("${interview.question.duplicate-ngram:2}") int nGram) {
        this.duplicateThreshold = duplicateThreshold;
        this.nGram = Math.max(1, nGram);
    }

    /**
     * 判断两段题目文本是否高度相似（达到重复阈值）。
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
        // 互相包含时直接判定重复
        if (normalizedSource.contains(normalizedTarget) || normalizedTarget.contains(normalizedSource)) {
            return true;
        }
        return calculateJaccardSimilarity(normalizedSource, normalizedTarget) >= duplicateThreshold;
    }

    /**
     * 计算两段文本的 Jaccard 相似度（0.0 - 1.0）。
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

    private Set<String> buildNGrams(String text) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i <= text.length() - nGram; i++) {
            grams.add(text.substring(i, i + nGram));
        }
        return grams;
    }

    private String normalize(String content) {
        return content.trim()
                .toLowerCase()
                .replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "")
                .replaceAll("\\s+", "");
    }
}
