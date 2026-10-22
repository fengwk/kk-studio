package fun.fengwk.kkstudio.platform.catalog.model.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigDerivation;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.util.ArrayList;
import java.util.List;

/** 将统一 AgentModel 行映射为公开 DTO。 */
@Component
public class AgentModelConverter {

  private final AgentModelRuntimeConfigParser runtimeConfigParser;
  private final AgentModelGroupConfigCodec groupCodec;
  private final AgentModelRepository modelRepository;

  public AgentModelConverter(
      AgentModelRuntimeConfigParser runtimeConfigParser,
      AgentModelGroupConfigCodec groupCodec,
      AgentModelRepository modelRepository) {
    this.runtimeConfigParser = runtimeConfigParser;
    this.groupCodec = groupCodec;
    this.modelRepository = modelRepository;
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
      AgentModelGroupDTO group = decodeGroup(model);
      dto.setGroup(group);
      // GROUP 的 config 只是成员能力的只读派生摘要，不承载组级价格或 Variant。
      dto.setConfig(deriveGroupConfig(group));
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

  private AgentModelConfigDTO deriveGroupConfig(AgentModelGroupDTO group) {
    List<AgentModelConfigDTO> members = new ArrayList<>(group.getMembers().size());
    for (var member : group.getMembers()) {
      AgentModel memberModel = modelRepository.getByName(member.getModelName());
      if (memberModel == null || memberModel.getKind() != AgentModelKind.MODEL) {
        throw new AiValidationException(
            "agent_model", "group member model is missing: " + member.getModelName());
      }
      members.add(decodeConfig(memberModel));
    }
    try {
      return AgentModelGroupConfigDerivation.derive(members);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("agent_model", error.getMessage(), error);
    }
  }
}
