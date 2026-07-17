package fun.fengwk.kkstudio.core.agent.model.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;

/** Converts global model resources to public DTOs. */
@Component
public class AgentModelConverter {

  public AgentModelDTO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDTO dto = new AgentModelDTO();
    dto.setId(Long.toString(model.getId()));
    dto.setProviderId(Long.toString(model.getProviderId()));
    dto.setName(model.getName());
    dto.setDescription(model.getDescription());
    dto.setCapabilitiesJson(model.getCapabilitiesJson());
    dto.setConfigJson(model.getConfigJson());
    dto.setVersion(model.getVersion());
    dto.setCreateTime(model.getCreateTime());
    dto.setUpdateTime(model.getUpdateTime());
    return dto;
  }
}
