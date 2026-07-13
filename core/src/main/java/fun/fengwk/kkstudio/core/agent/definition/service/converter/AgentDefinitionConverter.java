package fun.fengwk.kkstudio.core.agent.definition.service.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Component
public class AgentDefinitionConverter {

  private final ObjectMapper objectMapper;

  public AgentDefinitionDTO convert(AgentDefinition definition) {
    if (definition == null) {
      return null;
    }
    AgentDefinitionDTO dto = new AgentDefinitionDTO();
    dto.setId(Long.toString(definition.getId()));
    dto.setWorkspaceId(Long.toString(definition.getWorkspaceId()));
    dto.setName(definition.getName());
    dto.setDescription(definition.getDescription());
    dto.setSystemPrompt(definition.getSystemPrompt());
    dto.setModelId(Long.toString(definition.getModelId()));
    dto.setVariant(definition.getVariant());
    dto.setConfig(readConfig(definition.getConfigJson()));
    dto.setVersion(definition.getVersion());
    dto.setCreateTime(definition.getCreateTime());
    dto.setUpdateTime(definition.getUpdateTime());
    return dto;
  }

  private AgentDefinitionConfigDTO readConfig(String configJson) {
    try {
      return objectMapper.readValue(configJson, AgentDefinitionConfigDTO.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored agent definition config is invalid", e);
    }
  }
}
