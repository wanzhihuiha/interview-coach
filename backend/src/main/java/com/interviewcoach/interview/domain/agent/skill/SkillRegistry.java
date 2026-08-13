package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewPhase;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Spring 收集的面试环节 Skill 注册表。
 *
 * <p>构造时扫描每个实现支持的第一个环节并建立内存映射，Coordinator 随后按当前环节解析。
 * 多个实现声明同一环节时保留列表中的首个实现；任一实现不支持任何已知环节会阻断构造。</p>
 */
@Component
public class SkillRegistry {

    /** 环节到首个匹配 Skill Bean 的内存映射。 */
    private final Map<InterviewPhase, InterviewSkill> skillMap;

    /**
     * 从 Spring 注入的 Skill 列表构建注册表；重复环节沿用首个实现，不覆盖已有映射。
     */
    public SkillRegistry(List<InterviewSkill> skills) {
        this.skillMap = skills.stream()
                .collect(Collectors.toMap(
                        skill -> resolvePhase(skill),
                        skill -> skill,
                        (a, b) -> a
                ));
    }

    /** 按非空当前环节获取 Skill；没有注册实现时显式失败并阻断该轮协调。 */
    public InterviewSkill resolve(InterviewPhase phase) {
        InterviewSkill skill = skillMap.get(phase);
        if (skill == null) {
            throw new IllegalStateException("未找到环节对应的 Skill: " + phase);
        }
        return skill;
    }

    /** 按枚举顺序返回实现声明支持的第一个环节；没有匹配项时阻断注册表创建。 */
    private InterviewPhase resolvePhase(InterviewSkill skill) {
        for (InterviewPhase phase : InterviewPhase.values()) {
            if (skill.supports(phase)) {
                return phase;
            }
        }
        throw new IllegalStateException("Skill 未声明支持的环节: " + skill.getClass().getSimpleName());
    }
}
