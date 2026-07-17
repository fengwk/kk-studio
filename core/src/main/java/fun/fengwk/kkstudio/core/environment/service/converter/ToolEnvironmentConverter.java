package fun.fengwk.kkstudio.core.environment.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentDTO;

/** Converts Environment rows to public DTOs. */
@Component
public class ToolEnvironmentConverter {

  public ToolEnvironmentDTO convert(ToolEnvironment environment) {
    if (environment == null) {
      return null;
    }
    ToolEnvironmentDTO dto = new ToolEnvironmentDTO();
    dto.setId(Long.toString(environment.getId()));
    dto.setName(environment.getName());
    dto.setDescription(environment.getDescription());
    dto.setCapabilitiesJson(environment.getCapabilitiesJson());
    dto.setLastSeenAt(environment.getLastSeenAt());
    dto.setVersion(environment.getVersion());
    dto.setCreateTime(environment.getCreateTime());
    dto.setUpdateTime(environment.getUpdateTime());
    return dto;
  }
}
