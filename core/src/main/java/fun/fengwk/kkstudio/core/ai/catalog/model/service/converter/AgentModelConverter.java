package fun.fengwk.kkstudio.core.ai.catalog.model.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;

/**
 * Field-mapper that converts {@link AgentModel} rows into public DTOs. All structured-config
 * parsing is delegated to {@link AgentModelRuntimeConfigParser} so we never duplicate JSON shape
 * handling here.
 */
@Component
public class AgentModelConverter {

  private final AgentModelRuntimeConfigParser runtimeConfigParser;

  public AgentModelConverter(AgentModelRuntimeConfigParser runtimeConfigParser) {
    this.runtimeConfigParser = runtimeConfigParser;
  }

  public AgentModelDTO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDTO dto = new AgentModelDTO();
    dto.setProviderName(model.getProviderName());
    dto.setName(model.getName());
    dto.setDescription(model.getDescription());
    dto.setConfig(decodeConfig(model));
    dto.setVersion(CatalogVersions.format(model.getVersion()));
    dto.setCreateTime(model.getCreateTime());
    dto.setUpdateTime(model.getUpdateTime());
    return dto;
  }

  private AgentModelConfigDTO decodeConfig(AgentModel model) {
    String configJson = model.getConfigJson();
    if (configJson == null || configJson.isBlank()) {
      throw new AiValidationException(
          "agent_model",
          "agent model config is required: " + model.getProviderName() + "/" + model.getName());
    }
    return runtimeConfigParser.decode(configJson);
  }
}
