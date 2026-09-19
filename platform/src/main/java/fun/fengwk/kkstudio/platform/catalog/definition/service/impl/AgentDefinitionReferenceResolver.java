package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.CurrentSkill;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 解析全局 Agent definition、model 与 Skill 名引用。 */
@AllArgsConstructor
@Component
final class AgentDefinitionReferenceResolver {

  private static final String DEFINITION_RESOURCE = "agent_definition";
  private static final String MODEL_RESOURCE = "agent_model";
  private static final String SKILL_RESOURCE = "skill";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;
  private final SkillCatalogRepository skillCatalogRepository;

  AgentDefinition requireAgent(String name) {
    AgentDefinition definition = agentDefinitionRepository.getByName(name);
    if (definition == null) {
      throw new AiResourceNotFoundException(DEFINITION_RESOURCE);
    }
    return definition;
  }

  AgentDefinition requireAgentForUpdate(String name) {
    AgentDefinition definition = agentDefinitionRepository.getByNameForUpdate(name);
    if (definition == null) {
      throw new AiResourceNotFoundException(DEFINITION_RESOURCE);
    }
    return definition;
  }

  AgentModel requireModel(String providerName, String modelName) {
    AgentModel model = agentModelRepository.getByProviderNameAndName(providerName, modelName);
    if (model == null) {
      throw new AiResourceNotFoundException(MODEL_RESOURCE);
    }
    return model;
  }

  AgentModel requireModelForUpdate(String providerName, String modelName) {
    AgentModel model =
        agentModelRepository.getByProviderNameAndNameForUpdate(providerName, modelName);
    if (model == null) {
      throw new AiResourceNotFoundException(MODEL_RESOURCE);
    }
    return model;
  }

  /**
   * 锁定并校验 Agent 选中的全局 Skill 名。
   *
   * <p>按 {@code name} 升序对全部选中名取当前 Skill 行锁，与 package 替换/删除的锁顺序一致，因此 Agent 的引用不会在并发 package
   * 变更中被静默悬空；任一名称不存在于全局目录时确定性拒绝。Agent 不再需要 Environment 参与。
   */
  void requireCurrentSkills(List<String> skillNames) {
    if (skillNames == null || skillNames.isEmpty()) {
      return;
    }
    Set<String> names = new TreeSet<>(skillNames);
    Set<String> locked = new TreeSet<>();
    for (CurrentSkill skill : skillCatalogRepository.lockCurrentSkillsByNames(names)) {
      locked.add(skill.getName());
    }
    List<String> missing = names.stream().filter(name -> !locked.contains(name)).sorted().toList();
    if (!missing.isEmpty()) {
      throw new AiValidationException(
          SKILL_RESOURCE, "unknown agent skill names: " + String.join(", ", missing));
    }
  }

  /**
   * 创建 Agent 时锁定并校验 task allowlist 中的既有 Agent。
   *
   * <p>自身引用无需预先存在：目标行将在同一事务中插入；其它名称仍必须存在并加锁。
   */
  void requireSubagentsForCreate(String agentName, List<String> names) {
    for (String name : names.stream().sorted().toList()) {
      if (!name.equals(agentName)) {
        requireAgentForUpdate(name);
      }
    }
  }

  /**
   * 以统一名称顺序锁定待更新 Agent 与其新 allowlist，避免交叉引用更新形成反向行锁顺序。
   *
   * @return 已锁定的待更新 Agent
   */
  AgentDefinition requireAgentAndSubagentsForUpdate(String agentName, List<String> subagentNames) {
    TreeSet<String> names = new TreeSet<>(subagentNames);
    names.add(agentName);
    AgentDefinition target = null;
    for (String name : names) {
      AgentDefinition definition = requireAgentForUpdate(name);
      if (name.equals(agentName)) {
        target = definition;
      }
    }
    return target;
  }

  void ensureNotReferencedAsSubagent(String name) {
    if (agentDefinitionRepository.existsReferencingSubagent(name)) {
      throw new AiInUseException(
          DEFINITION_RESOURCE,
          DEFINITION_RESOURCE + " is referenced by another agent subagents allowlist: " + name);
    }
  }
}
