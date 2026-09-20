package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** 解析全局 Agent definition、model 与 Skill 引用。 */
@AllArgsConstructor
@Component
final class AgentDefinitionReferenceResolver {

  private static final String DEFINITION_RESOURCE = "agent_definition";
  private static final String MODEL_RESOURCE = "agent_model";
  private static final String SKILL_RESOURCE = "skill";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;
  private final SkillCatalogQueryService skillCatalogQueryService;

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
   * 校验 Agent 选中的全局 Skill 引用必须存在。
   *
   * <p>对每个引用读取 Package 权威事实与其当前 manifest；任一 Package 或 Skill 缺失时确定性拒绝。 不再需要行锁，因为 Package 的 current
   * commit 与 manifest 是单表行的原子事实。
   */
  void requireCurrentSkills(List<SkillRefDTO> skills) {
    if (skills == null || skills.isEmpty()) {
      return;
    }
    List<String> missing = new ArrayList<>();
    for (SkillRefDTO ref : skills) {
      String identity = ref == null ? "null" : ref.getPackageName() + "/" + ref.getName();
      SkillPackage pkg =
          ref == null ? null : skillCatalogQueryService.getPackage(ref.getPackageName());
      if (pkg == null || pkg.findSkill(ref.getName()) == null) {
        if (!missing.contains(identity)) {
          missing.add(identity);
        }
      }
    }
    if (!missing.isEmpty()) {
      missing.sort(String::compareTo);
      throw new AiValidationException(
          SKILL_RESOURCE, "unknown agent skills: " + String.join(", ", missing));
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
