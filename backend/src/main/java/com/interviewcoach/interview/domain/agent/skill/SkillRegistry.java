package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewPhase;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Skill 注册表：根据当前面试环节加载对应的 Skill。
 */
@Component
public class SkillRegistry {

    private final Map<InterviewPhase, InterviewSkill> skillMap;

    public SkillRegistry(List<InterviewSkill> skills) {
        this.skillMap = skills.stream()
                .collect(Collectors.toMap(
                        skill -> resolvePhase(skill),
                        skill -> skill,
                        (a, b) -> a
                ));
    }

    /**
     * 根据环节获取 Skill。
     */
    public InterviewSkill resolve(InterviewPhase phase) {
        InterviewSkill skill = skillMap.get(phase);
        if (skill == null) {
            throw new IllegalStateException("未找到环节对应的 Skill: " + phase);
        }
        return skill;
    }

    private InterviewPhase resolvePhase(InterviewSkill skill) {
        for (InterviewPhase phase : InterviewPhase.values()) {
            if (skill.supports(phase)) {
                return phase;
            }
        }
        throw new IllegalStateException("Skill 未声明支持的环节: " + skill.getClass().getSimpleName());
    }
}
