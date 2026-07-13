package fun.fengwk.kkstudio.core.workspace.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.workspace.service.model.Workspace;

/**
 * @author fengwk
 */
public interface WorkspaceRepository {

  Page<Workspace> page(PageQuery pageQuery);

  Workspace getById(long id);

  Workspace getByName(String name);

  boolean create(Workspace workspace);

  boolean updateById(Workspace workspace);

  boolean deleteById(long id);

  boolean hasResources(long id);
}
