package com.interviewcoach.resume.application.service;

import com.interviewcoach.resume.domain.model.UserProfileData;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 供事实解析 Worker 和画像支持组件统一事实画像中的集合、技能名称及经验等级。
 * 组件只根据模型返回或用户提交的明示事实做确定性整理，不补充新的简历事实，也不调用外部模型。
 */
@Component
public class ResumeProfileNormalizer {

    /**
     * 当前技能名称别名表，键按小写比较，值作为草稿和正式画像中的统一展示名称。
     * 精确别名集合的产品依据缺失；增加映射会合并更多输入名称，删除映射会让后续画像保留原始写法。
     */
    private static final Map<String, String> SKILL_ALIASES = Map.ofEntries(
            Map.entry("java se", "Java"),
            Map.entry("java语言", "Java"),
            Map.entry("springboot", "Spring Boot"),
            Map.entry("spring boot", "Spring Boot"),
            Map.entry("mysql", "MySQL"),
            Map.entry("redis", "Redis"));
    /**
     * 只有原文明确出现这些中文等级时才保留技能等级，其他模型输出会被丢弃。
     * 该集合与事实提取提示词当前列出的四个等级一致；调整会改变草稿和正式画像可接受的等级值。
     */
    private static final Set<String> ALLOWED_LEVELS = Set.of("精通", "熟练", "熟悉", "了解");

    /**
     * 原地统一空集合、技能别名和明示技能等级，供草稿与正式画像共用。
     */
    public UserProfileData normalize(UserProfileData data) {
        if (data.getBasicInfo() == null) {
            data.setBasicInfo(new UserProfileData.BasicInfo());
        }

        LinkedHashSet<String> normalizedSkills = new LinkedHashSet<>();
        if (data.getSkillTags() != null) {
            // 按输入顺序统一别名、删除空值并去重，结果供草稿、正式画像和模型分析共同使用。
            data.getSkillTags().stream()
                    .map(this::normalizeSkillName)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(normalizedSkills::add);
        }
        data.setSkillTags(List.copyOf(normalizedSkills));

        Map<String, String> normalizedLevels = new LinkedHashMap<>();
        if (data.getSkillLevel() != null) {
            // 只保留明示允许等级，并把仅出现在等级映射中的技能补入技能标签集合。
            data.getSkillLevel().forEach((skill, level) -> {
                String normalizedSkill = normalizeSkillName(skill);
                String normalizedLevel = trimToNull(level);
                if (normalizedSkill != null && normalizedLevel != null && ALLOWED_LEVELS.contains(normalizedLevel)) {
                    normalizedLevels.put(normalizedSkill, normalizedLevel);
                    normalizedSkills.add(normalizedSkill);
                }
            });
        }
        data.setSkillTags(List.copyOf(normalizedSkills));
        data.setSkillLevel(Collections.unmodifiableMap(new LinkedHashMap<>(normalizedLevels)));

        List<UserProfileData.ProjectExperience> projects = data.getProjectExperience() == null
                ? new ArrayList<>() : new ArrayList<>(data.getProjectExperience());
        projects = projects.stream().filter(Objects::nonNull).toList();
        // 项目技术栈沿用同一别名规则并按首次出现顺序去重，空集合统一为不可变空列表。
        projects.forEach(project -> {
            if (project.getTechStack() == null) {
                project.setTechStack(List.of());
            } else {
                project.setTechStack(project.getTechStack().stream()
                        .map(this::normalizeSkillName)
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .toList());
            }
        });
        data.setProjectExperience(List.copyOf(projects));

        List<UserProfileData.WorkExperience> work = data.getWorkExperience() == null
                ? new ArrayList<>() : new ArrayList<>(data.getWorkExperience());
        work = work.stream().filter(Objects::nonNull).toList();
        // 工作亮点只做去空白和去重，不推断或重写原文事实。
        work.forEach(item -> {
            if (item.getHighlights() == null) {
                item.setHighlights(List.of());
            } else {
                item.setHighlights(item.getHighlights().stream()
                        .map(this::trimToNull)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
            }
        });
        data.setWorkExperience(List.copyOf(work));
        return data;
    }

    /**
     * 仅根据明确工作年限或经历时间做确定性分级，不读取模型推断结果。
     */
    public String inferExperienceLevel(UserProfileData data) {
        String workingYears = data.getBasicInfo() != null ? data.getBasicInfo().getWorkingYears() : null;
        if (workingYears != null && !workingYears.isBlank()) {
            // 基本信息中首个“数字 + 年”优先于经历累加，直接作为总工作年限分级。
            Matcher yearMatcher = Pattern.compile("(\\d+)\\s*年").matcher(workingYears);
            if (yearMatcher.find()) {
                return classifyExperienceLevel(Integer.parseInt(yearMatcher.group(1)));
            }
        }
        List<UserProfileData.WorkExperience> workExperience = data.getWorkExperience() == null
                ? List.of() : data.getWorkExperience();
        // 未明示总年限时逐段解析经历并相加；重叠经历不会去重，这是当前确定性规则。
        int years = workExperience.stream()
                .filter(Objects::nonNull)
                .mapToInt(this::extractYears)
                .sum();
        return classifyExperienceLevel(years);
    }

    /** 对单个技能去首尾空白并按小写别名表归一；无法形成文本时返回 null。 */
    private String normalizeSkillName(String skill) {
        String value = trimToNull(skill);
        if (value == null) {
            return null;
        }
        return SKILL_ALIASES.getOrDefault(value.toLowerCase(Locale.ROOT), value);
    }

    /** 将 null 或纯空白文本收敛为 null，其他文本返回去首尾空白后的值。 */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 从一段经历时长提取年数：优先读取“数字 + 年”，否则使用首个起始年份和可选结束年份。
     * 只有一个四位年份时以当前系统年份作为结束年；无法解析或结果为负时返回 0。
     */
    private int extractYears(UserProfileData.WorkExperience work) {
        String duration = work.getDuration();
        if (duration == null || duration.isBlank()) {
            return 0;
        }
        Matcher durationMatcher = Pattern.compile("(\\d+)\\s*年").matcher(duration);
        if (durationMatcher.find()) {
            return Integer.parseInt(durationMatcher.group(1));
        }
        Matcher yearMatcher = Pattern.compile("(\\d{4})").matcher(duration);
        if (!yearMatcher.find()) {
            return 0;
        }
        int startYear = Integer.parseInt(yearMatcher.group(1));
        int endYear = yearMatcher.find() ? Integer.parseInt(yearMatcher.group(1)) : Year.now().getValue();
        return Math.max(0, endYear - startYear);
    }

    /**
     * 将确定性年数映射为内部等级编码：7 年及以上为 SENIOR，3 至 6 年为 MID，其余为 JUNIOR。
     * 3 年和 7 年阈值的产品依据缺失；调大阈值会降低相同年数的等级，调小则会提升。
     */
    private String classifyExperienceLevel(int years) {
        if (years >= 7) {
            return "SENIOR";
        }
        if (years >= 3) {
            return "MID";
        }
        return "JUNIOR";
    }
}
