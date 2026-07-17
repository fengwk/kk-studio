package fun.fengwk.kkstudio.core.environment.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.model.ToolEnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentUpdateDTO;

/** Global Environment CRUD surface; capabilities and last-seen are managed elsewhere. */
public interface ToolEnvironmentService {

  Page<ToolEnvironmentDTO> pageEnvironments(PageQuery pageQuery);

  ToolEnvironmentDTO createEnvironment(ToolEnvironmentCreateDTO createDTO);

  ToolEnvironmentDTO updateEnvironment(String id, ToolEnvironmentUpdateDTO updateDTO);

  void deleteEnvironment(String id);
}
