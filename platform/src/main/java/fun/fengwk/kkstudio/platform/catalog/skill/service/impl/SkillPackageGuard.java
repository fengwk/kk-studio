package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;

import java.util.Collection;
import java.util.List;

/** Skill package 的查找与引用保护检查。 */
@AllArgsConstructor
@Component
final class SkillPackageGuard {

  private static final String RESOURCE = SkillPackageMutationFactory.RESOURCE;

  private final SkillCatalogRepository skillCatalogRepository;
  private final AgentDefinitionRepository agentDefinitionRepository;

  /** 以 {@code FOR UPDATE} 锁定某 package 名当前的活跃版本；缺失则 404。 */
  SkillPackage requireActivePackageForUpdate(String packageName) {
    SkillPackage skillPackage = skillCatalogRepository.lockActivePackage(packageName);
    if (skillPackage == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return skillPackage;
  }

  /**
   * 按 {@code name} 升序 {@code FOR UPDATE} 锁定给定名称的当前 Skill 行。
   *
   * <p>与 Agent 创建/更新的选择校验共用同一把行锁顺序，因此“校验引用”与“移除 Skill”严格串行，不存在并发悬空引用。
   */
  void lockCurrentSkills(Collection<String> names) {
    skillCatalogRepository.lockCurrentSkillsByNames(names);
  }

  /**
   * 拒绝移除仍被任何 Agent 引用的 Skill。
   *
   * <p>只有从当前目录真正消失的名称才算移除；保留同名 Skill（内容可变更）不构成移除。
   */
  void ensureRemovable(Collection<String> removedNames) {
    List<String> referenced =
        removedNames.stream()
            .filter(agentDefinitionRepository::existsReferencingSkill)
            .sorted()
            .toList();
    if (!referenced.isEmpty()) {
      throw new AiInUseException(
          RESOURCE, "skills are still referenced by agents: " + String.join(", ", referenced));
    }
  }
}
