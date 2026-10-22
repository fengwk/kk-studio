package fun.fengwk.kkstudio.platform.catalog.model.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

/** 将统一 AgentModel 行映射为公开 DTO。 */
@Component
public class AgentModelConverter {

  private final AgentModelRuntimeConfigParser runtimeConfigParser;
  private final AgentModelGroupConfigCodec groupCodec;

  public AgentModelConverter(
      AgentModelRuntimeConfigParser runtimeConfigParser, AgentModelGroupConfigCodec groupCodec) {
    this.runtimeConfigParser = runtimeConfigParser;
    this.groupCodec = groupCodec;
  }

  public AgentModelDTO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDTO dto = new AgentModelDTO();
    dto.setName(model.getName());
    dto.setKind(model.getKind());
    dto.setDescription(model.getDescription());
    if (model.getKind() == AgentModelKind.MODEL) {
      dto.setProviderName(model.getProviderName());
      dto.setModelId(model.getModelId());
      dto.setConfig(decodeConfig(model));
    } else if (model.getKind() == AgentModelKind.GROUP) {
      dto.setGroup(decodeGroup(model));
    } else {
      throw new AiValidationException(
          "agent_model", "unknown agent model kind: " + model.getKind());
    }
    dto.setVersion(CatalogVersions.format(model.getVersion()));
    dto.setCreateTime(model.getCreateTime());
    dto.setUpdateTime(model.getUpdateTime());
    return dto;
  }

  private AgentModelConfigDTO decodeConfig(AgentModel model) {
    String configJson = model.getConfigJson();
    if (configJson == null || configJson.isBlank()) {
      throw new AiValidationException(
          "agent_model", "agent model config is required: " + model.getName());
    }
    return runtimeConfigParser.decode(configJson);
  }

  private AgentModelGroupDTO decodeGroup(AgentModel model) {
    String groupConfigJson = model.getGroupConfigJson();
    if (groupConfigJson == null || groupConfigJson.isBlank()) {
      throw new AiValidationException(
          "agent_model", "model group config is required: " + model.getName());
    }
    return groupCodec.decode(groupConfigJson);
  }
}
