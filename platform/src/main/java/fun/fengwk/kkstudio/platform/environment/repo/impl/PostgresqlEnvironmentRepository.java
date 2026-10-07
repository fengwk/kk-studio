package fun.fengwk.kkstudio.platform.environment.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.repo.impl.mapper.EnvironmentMapper;
import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentInstallConfigs;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** 基于 PostgreSQL 的 Environment 仓库实现。 */
@AllArgsConstructor
@Repository
public class PostgresqlEnvironmentRepository implements EnvironmentRepository {

  private final EnvironmentMapper environmentMapper;
  private final PostgresqlEnvironmentChangeNotifier notifier;

  @Override
  public List<Environment> listNewestFirst() {
    return environmentMapper.listNewestFirst().stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<Environment> listAll() {
    return environmentMapper.listAll().stream().map(this::toModel).collect(Collectors.toList());
  }

  @Override
  public Environment getById(UUID id) {
    return toModel(environmentMapper.getById(id));
  }

  @Override
  public Environment getByName(String name) {
    return toModel(environmentMapper.getByName(name));
  }

  @Override
  public Environment getByRegistrationToken(String registrationToken) {
    return toModel(environmentMapper.getByRegistrationToken(registrationToken));
  }

  @Override
  public Environment lockById(UUID id) {
    return toModel(environmentMapper.lockById(id));
  }

  @Override
  public Environment lockForKeyShare(UUID id) {
    return toModel(environmentMapper.lockForKeyShare(id));
  }

  @Override
  public boolean existsByName(String name) {
    return environmentMapper.existsByName(name);
  }

  @Override
  public boolean create(Environment environment) {
    boolean created = environmentMapper.insert(toDO(environment)) == 1;
    if (created) {
      notifier.environmentChanged(environment.getId());
    }
    return created;
  }

  @Override
  public boolean updateById(Environment environment, long expectedVersion) {
    boolean updated = environmentMapper.updateById(toDO(environment), expectedVersion) == 1;
    if (updated) {
      notifier.environmentChanged(environment.getId());
    }
    return updated;
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    boolean deleted = environmentMapper.deleteById(id, expectedVersion) == 1;
    if (deleted) {
      // 连接行随 environment 级联删除，同一 environment 的通知已覆盖级联，不另造连接级来源。
      notifier.environmentChanged(id);
    }
    return deleted;
  }

  private EnvironmentDO toDO(Environment model) {
    if (model == null) {
      return null;
    }
    EnvironmentDO target = new EnvironmentDO();
    target.setId(model.getId());
    target.setName(model.getName());
    target.setRegistrationToken(model.getRegistrationToken());
    target.setInstallConfigJson(EnvironmentInstallConfigs.write(model.getInstallConfig()));
    target.setVersion(model.getVersion());
    target.setCreateTime(model.getCreateTime());
    target.setUpdateTime(model.getUpdateTime());
    return target;
  }

  private Environment toModel(EnvironmentDO row) {
    if (row == null) {
      return null;
    }
    Environment target = new Environment();
    target.setId(row.getId());
    target.setName(row.getName());
    target.setRegistrationToken(row.getRegistrationToken());
    target.setInstallConfig(EnvironmentInstallConfigs.read(row.getInstallConfigJson()));
    target.setVersion(row.getVersion());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }
}
