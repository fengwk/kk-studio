package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;

import java.util.Collection;
import java.util.List;

/** Skill Package 的查找与 Agent 引用保护检查。 */
@AllArgsConstructor
@Component
final class SkillPackageGuard {

  static final String RESOURCE = "skill_package";

  private final SkillPackageRepository skillPackageRepository;
  private final AgentDefinitionRepository agentDefinitionRepository;

  /** 以 {@code FOR UPDATE} 锁定某 Package；缺失则 404。 */
  SkillPackage requirePackageForUpdate(String packageName) {
    SkillPackage skillPackage = skillPackageRepository.lockPackage(packageName);
    if (skillPackage == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return skillPackage;
  }

  /**
   * 拒绝让仍被任何 Agent {@code SkillRef} 引用的 Skill 从 Package 中消失。
   *
   * <p>引用保护按精确的 {@code (packageName, name)} 判定：保留同名 Skill（内容随 commit 变化）不构成移除。
   */
  void ensureSkillsRemovable(String packageName, Collection<String> removedSkillNames) {
    List<String> referenced =
        removedSkillNames.stream()
            .filter(name -> agentDefinitionRepository.existsReferencingSkill(packageName, name))
            .sorted()
            .toList();
    if (!referenced.isEmpty()) {
      throw new AiInUseException(
          RESOURCE,
          "skills are still referenced by agents: "
              + packageName
              + "/"
              + String.join(", ", referenced));
    }
  }

  /** 从 manifest 中提取全部 Skill 名。 */
  static List<String> manifestNames(List<SkillManifestEntry> skills) {
    if (skills == null) {
      return List.of();
    }
    return skills.stream().map(SkillManifestEntry::name).toList();
  }
}
