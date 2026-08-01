package fun.fengwk.kkstudio.core.ai.catalog.definition.service.converter;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelRef;

/** Converts global Agent definitions to public DTOs. */
@AllArgsConstructor
@Component
public class AgentDefinitionConverter {

  private final AgentDefinitionConfigCodec configCodec;

  public AgentDefinitionDTO convert(AgentDefinition definition) {
    if (definition == null) {
      return null;
    }
    AgentDefinitionDTO dto = new AgentDefinitionDTO();
    dto.setName(definition.getName());
    dto.setDescription(definition.getDescription());
    dto.setSystemPrompt(definition.getSystemPrompt());
    dto.setModel(
        new ModelRef(definition.getModelProviderName(), definition.getModelName()).toString());
    dto.setVariant(definition.getVariant());
    dto.setConfig(configCodec.decode(definition.getConfigJson()));
    dto.setVersion(CatalogVersions.format(definition.getVersion()));
    dto.setCreateTime(definition.getCreateTime());
    dto.setUpdateTime(definition.getUpdateTime());
    return dto;
  }
}
