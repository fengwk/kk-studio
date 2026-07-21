package fun.fengwk.kkstudio.core.agent.model.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;

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
    dto.setId(Long.toString(model.getId()));
    dto.setProviderId(Long.toString(model.getProviderId()));
    dto.setName(model.getName());
    dto.setDescription(model.getDescription());
    dto.setConfig(decodeConfig(model));
    dto.setVersion(model.getVersion());
    dto.setCreateTime(model.getCreateTime());
    dto.setUpdateTime(model.getUpdateTime());
    return dto;
  }

  private AgentModelConfigDTO decodeConfig(AgentModel model) {
    String configJson = model.getConfigJson();
    if (configJson == null || configJson.isBlank()) {
      throw new IllegalStateException("agent model config_json is required: " + model.getId());
    }
    return runtimeConfigParser.decode(configJson);
  }
}
