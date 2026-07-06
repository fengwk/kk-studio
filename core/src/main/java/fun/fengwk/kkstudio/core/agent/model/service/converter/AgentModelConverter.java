package fun.fengwk.kkstudio.core.agent.model.service.converter;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import org.springframework.stereotype.Component;

/**
 * @author fengwk
 */
@Component
public class AgentModelConverter {

  public AgentModelDTO convert(AgentModel model, AgentProvider provider) {
    if (model == null) {
      return null;
    }
    AgentModelDTO modelDTO = new AgentModelDTO();
    modelDTO.setId(model.getId());
    modelDTO.setProviderId(model.getProviderId());
    modelDTO.setProviderName(provider == null ? null : provider.getName());
    modelDTO.setName(model.getName());
    modelDTO.setDescription(model.getDescription());
    modelDTO.setCapabilitiesJson(model.getCapabilitiesJson());
    modelDTO.setLimitJson(model.getLimitJson());
    modelDTO.setPricingJson(model.getPricingJson());
    modelDTO.setDefaultVariant(model.getDefaultVariant());
    modelDTO.setVariantsJson(model.getVariantsJson());
    modelDTO.setCreateTime(model.getCreateTime());
    modelDTO.setUpdateTime(model.getUpdateTime());
    return modelDTO;
  }
}
