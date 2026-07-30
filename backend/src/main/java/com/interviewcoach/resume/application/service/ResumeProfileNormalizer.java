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
 * 统一事实画像的空集合、技能别名和经验等级派生规则。
 */
@Component
public class ResumeProfileNormalizer {

    private static final Map<String, String> SKILL_ALIASES = Map.ofEntries(
            Map.entry("java se", "Java"),
            Map.entry("java语言", "Java"),
            Map.entry("springboot", "Spring Boot"),
            Map.entry("spring boot", "Spring Boot"),
            Map.entry("mysql", "MySQL"),
            Map.entry("redis", "Redis"));
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
            data.getSkillTags().stream()
                    .map(this::normalizeSkillName)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(normalizedSkills::add);
        }
        data.setSkillTags(List.copyOf(normalizedSkills));

        Map<String, String> normalizedLevels = new LinkedHashMap<>();
        if (data.getSkillLevel() != null) {
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
            Matcher yearMatcher = Pattern.compile("(\\d+)\\s*年").matcher(workingYears);
            if (yearMatcher.find()) {
                return classifyExperienceLevel(Integer.parseInt(yearMatcher.group(1)));
            }
        }
        List<UserProfileData.WorkExperience> workExperience = data.getWorkExperience() == null
                ? List.of() : data.getWorkExperience();
        int years = workExperience.stream()
                .filter(Objects::nonNull)
                .mapToInt(this::extractYears)
                .sum();
        return classifyExperienceLevel(years);
    }

    private String normalizeSkillName(String skill) {
        String value = trimToNull(skill);
        if (value == null) {
            return null;
        }
        return SKILL_ALIASES.getOrDefault(value.toLowerCase(Locale.ROOT), value);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

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
