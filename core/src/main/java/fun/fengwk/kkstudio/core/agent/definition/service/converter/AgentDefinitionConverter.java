package fun.fengwk.kkstudio.core.agent.definition.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;

/**
 * @author fengwk
 */
@Component
public class AgentDefinitionConverter {

  public AgentDefinitionDTO convert(
      AgentDefinition agentDefinition, AgentProvider provider, AgentModel model) {
    if (agentDefinition == null) {
      return null;
    }
    AgentDefinitionDTO agentDefinitionDTO = new AgentDefinitionDTO();
    agentDefinitionDTO.setId(agentDefinition.getId());
    agentDefinitionDTO.setName(agentDefinition.getName());
    agentDefinitionDTO.setDescription(agentDefinition.getDescription());
    agentDefinitionDTO.setSystemPrompt(agentDefinition.getSystemPrompt());
    agentDefinitionDTO.setDefaultProviderId(agentDefinition.getDefaultProviderId());
    agentDefinitionDTO.setDefaultProviderName(provider == null ? null : provider.getName());
    agentDefinitionDTO.setDefaultModelId(agentDefinition.getDefaultModelId());
    agentDefinitionDTO.setDefaultModelName(model == null ? null : model.getName());
    agentDefinitionDTO.setDefaultVariant(agentDefinition.getDefaultVariant());
    agentDefinitionDTO.setToolsJson(agentDefinition.getToolsJson());
    agentDefinitionDTO.setSubagentsJson(agentDefinition.getSubagentsJson());
    agentDefinitionDTO.setSkillsJson(agentDefinition.getSkillsJson());
    agentDefinitionDTO.setCreateTime(agentDefinition.getCreateTime());
    agentDefinitionDTO.setUpdateTime(agentDefinition.getUpdateTime());
    return agentDefinitionDTO;
  }
}
