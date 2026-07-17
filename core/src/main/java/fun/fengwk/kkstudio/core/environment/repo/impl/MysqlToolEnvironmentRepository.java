package fun.fengwk.kkstudio.core.environment.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.repo.impl.mapper.ToolEnvironmentMapper;
import fun.fengwk.kkstudio.core.environment.repo.impl.model.ToolEnvironmentDO;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;

import java.time.LocalDateTime;
import java.util.List;

/** MySQL-backed global Environment registry repository. */
@AllArgsConstructor
@Repository
public class MysqlToolEnvironmentRepository implements ToolEnvironmentRepository {

  private final ToolEnvironmentMapper toolEnvironmentMapper;

  @Override
  public Page<ToolEnvironment> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<ToolEnvironmentDO> rows = toolEnvironmentMapper.page(offset, limit);
    return Pages.page(pageQuery, rows, toolEnvironmentMapper.count()).map(this::toModel);
  }

  @Override
  public ToolEnvironment getById(long id) {
    return toModel(toolEnvironmentMapper.getById(id));
  }

  @Override
  public ToolEnvironment getByName(String name) {
    return toModel(toolEnvironmentMapper.getByName(name));
  }

  @Override
  public boolean create(ToolEnvironment environment) {
    return toolEnvironmentMapper.insert(toDO(environment)) == 1;
  }

  @Override
  public boolean updateById(ToolEnvironment environment) {
    return toolEnvironmentMapper.updateById(toDO(environment)) == 1;
  }

  @Override
  public boolean deleteById(long id) {
    return toolEnvironmentMapper.deleteById(id) == 1;
  }

  @Override
  public boolean updateCapabilities(long id, String capabilitiesJson, LocalDateTime lastSeenAt) {
    return toolEnvironmentMapper.updateCapabilities(id, capabilitiesJson, lastSeenAt) == 1;
  }

  @Override
  public boolean heartbeat(long id, LocalDateTime lastSeenAt) {
    return toolEnvironmentMapper.heartbeat(id, lastSeenAt) == 1;
  }

  @Override
  public long countToolInvocations(long environmentId) {
    return toolEnvironmentMapper.countToolInvocations(environmentId);
  }

  private ToolEnvironmentDO toDO(ToolEnvironment environment) {
    if (environment == null) {
      return null;
    }
    ToolEnvironmentDO target = new ToolEnvironmentDO();
    target.setId(environment.getId());
    target.setName(environment.getName());
    target.setDescription(environment.getDescription());
    target.setCapabilitiesJson(environment.getCapabilitiesJson());
    target.setLastSeenAt(environment.getLastSeenAt());
    return target;
  }

  private ToolEnvironment toModel(ToolEnvironmentDO row) {
    if (row == null) {
      return null;
    }
    ToolEnvironment target = new ToolEnvironment();
    target.setId(row.getId());
    target.setName(row.getName());
    target.setDescription(row.getDescription());
    target.setCapabilitiesJson(row.getCapabilitiesJson());
    target.setLastSeenAt(row.getLastSeenAt());
    target.setVersion(row.getVersion());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }
}
