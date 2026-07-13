package fun.fengwk.kkstudio.core.workspace.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.workspace.repo.WorkspaceRepository;
import fun.fengwk.kkstudio.core.workspace.repo.impl.mapper.WorkspaceMapper;
import fun.fengwk.kkstudio.core.workspace.repo.impl.model.WorkspaceDO;
import fun.fengwk.kkstudio.core.workspace.service.model.Workspace;

import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlWorkspaceRepository implements WorkspaceRepository {

  private final WorkspaceMapper workspaceMapper;

  @Override
  public Page<Workspace> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<WorkspaceDO> results = workspaceMapper.pageAll(offset, limit);
    return Pages.page(pageQuery, results, workspaceMapper.countAll()).map(this::convert);
  }

  @Override
  public Workspace getById(long id) {
    return convert(workspaceMapper.getById(id));
  }

  @Override
  public Workspace getByName(String name) {
    return convert(workspaceMapper.getByName(name));
  }

  @Override
  public boolean create(Workspace workspace) {
    return workspaceMapper.insert(convert(workspace)) == 1;
  }

  @Override
  public boolean updateById(Workspace workspace) {
    return workspaceMapper.updateById(convert(workspace)) == 1;
  }

  @Override
  public boolean deleteById(long id) {
    return workspaceMapper.deleteById(id) == 1;
  }

  @Override
  public boolean hasResources(long id) {
    return workspaceMapper.countResources(id) > 0;
  }

  private WorkspaceDO convert(Workspace workspace) {
    if (workspace == null) {
      return null;
    }
    WorkspaceDO result = new WorkspaceDO();
    result.setId(workspace.getId());
    result.setName(workspace.getName());
    result.setSettingsJson(workspace.getSettingsJson());
    return result;
  }

  private Workspace convert(WorkspaceDO workspace) {
    if (workspace == null) {
      return null;
    }
    Workspace result = new Workspace();
    result.setId(workspace.getId());
    result.setName(workspace.getName());
    result.setSettingsJson(workspace.getSettingsJson());
    result.setVersion(workspace.getVersion());
    result.setCreateTime(workspace.getCreateTime());
    result.setUpdateTime(workspace.getUpdateTime());
    return result;
  }
}
