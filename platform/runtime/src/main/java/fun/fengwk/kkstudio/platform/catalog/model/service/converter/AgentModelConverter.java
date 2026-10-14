package fun.fengwk.kkstudio.platform.catalog.model.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;

/**
 * 将 {@link AgentModel} 行映射为公开 DTO 的字段映射器。所有结构化配置解析都委托给 {@link AgentModelRuntimeConfigParser}，此处绝不重复
 * JSON 形状处理。
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
    dto.setModelId(model.getModelId());
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
