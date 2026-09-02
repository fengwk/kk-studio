package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;

import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** 解析全局 Agent definition 与 model 引用。 */
@AllArgsConstructor
@Component
final class AgentDefinitionReferenceResolver {

  private static final String DEFINITION_RESOURCE = "agent_definition";
  private static final String MODEL_RESOURCE = "agent_model";
  private static final String ENVIRONMENT_RESOURCE = "environment";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;
  private final EnvironmentRepository environmentRepository;

  AgentDefinition requireAgent(String name) {
    AgentDefinition definition = agentDefinitionRepository.getByName(name);
    if (definition == null) {
      throw new AiResourceNotFoundException(
          DEFINITION_RESOURCE, DEFINITION_RESOURCE + " not found: " + name);
    }
    return definition;
  }

  AgentDefinition requireAgentForUpdate(String name) {
    AgentDefinition definition = agentDefinitionRepository.getByNameForUpdate(name);
    if (definition == null) {
      throw new AiResourceNotFoundException(
          DEFINITION_RESOURCE, DEFINITION_RESOURCE + " not found: " + name);
    }
    return definition;
  }

  AgentModel requireModel(String providerName, String modelName) {
    AgentModel model = agentModelRepository.getByProviderNameAndName(providerName, modelName);
    if (model == null) {
      throw new AiResourceNotFoundException(
          MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + providerName + "/" + modelName);
    }
    return model;
  }

  AgentModel requireModelForUpdate(String providerName, String modelName) {
    AgentModel model =
        agentModelRepository.getByProviderNameAndNameForUpdate(providerName, modelName);
    if (model == null) {
      throw new AiResourceNotFoundException(
          MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + providerName + "/" + modelName);
    }
    return model;
  }

  /** 锁定并校验可选的 Environment 引用，防止在创建/更新 Agent 时被并发删除。 */
  void requireEnvironmentForShare(UUID environmentId) {
    if (environmentId != null) {
      Environment environment = environmentRepository.lockForKeyShare(environmentId);
      if (environment == null) {
        throw new AiResourceNotFoundException(
            ENVIRONMENT_RESOURCE, ENVIRONMENT_RESOURCE + " not found: " + environmentId);
      }
    }
  }

  /** 锁定并校验 task allowlist 中的全部 Agent，防止其在当前配置写入事务中被并发删除。 */
  void requireSubagentsForUpdate(List<String> names) {
    for (String name : names.stream().sorted().toList()) {
      requireAgentForUpdate(name);
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
          DEFINITION_RESOURCE + " is referenced by an agent subagents allowlist: " + name);
    }
  }
}
