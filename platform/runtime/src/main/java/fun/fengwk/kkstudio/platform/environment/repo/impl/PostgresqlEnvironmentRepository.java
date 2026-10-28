package fun.fengwk.kkstudio.platform.environment.repo.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;
import fun.fengwk.kkstudio.platform.environment.repo.impl.store.EnvironmentJdbcStore;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentInstallConfigs;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于 PostgreSQL 的 Environment 仓库实现。
 *
 * <p>所有写方法都要求调用方已开启真实外层事务。稳定 Card 的 create/update/delete 在写入成功后于同一事务连接发布 {@code
 * environment_changed}，通知失败会连同写入一起回滚；安装 code 只写入 Environment 行，签发不推进版本，兑换在同一语句中清空 code、轮换 token
 * 并递增版本。
 */
@Repository
public class PostgresqlEnvironmentRepository implements EnvironmentRepository {

  private static final String SAVE_INSTALL_CODE_SQL =
      """
      update environment
      set install_code = jsonb_build_object(
          'codeHash', :codeHash,
          'expiresAt', :expiresAt
      )
      where id = :environmentId and version = :expectedVersion
      """;

  private static final String REDEEM_INSTALL_CODE_SQL =
      """
      update environment
      set install_code = null,
          registration_token_hash = :registrationTokenHash,
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = :environmentId
        and version = :expectedVersion
        and install_code is not null
        and install_code ->> 'codeHash' = :codeHash
        and (install_code ->> 'expiresAt')::timestamptz > statement_timestamp()
      """;

  private final EnvironmentJdbcStore environmentJdbcStore;
  private final PostgresqlEnvironmentChangeNotifier notifier;
  private final JdbcClient jdbcClient;

  @Autowired
  public PostgresqlEnvironmentRepository(
      EnvironmentJdbcStore environmentJdbcStore,
      PostgresqlEnvironmentChangeNotifier notifier,
      JdbcClient jdbcClient) {
    this.environmentJdbcStore =
        Objects.requireNonNull(environmentJdbcStore, "environmentJdbcStore");
    this.notifier = Objects.requireNonNull(notifier, "notifier");
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  @Override
  public List<Environment> listNewestFirst() {
    return environmentJdbcStore.listNewestFirst().stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<Environment> listAll() {
    return environmentJdbcStore.listAll().stream().map(this::toModel).collect(Collectors.toList());
  }

  @Override
  public Environment getById(UUID id) {
    return toModel(environmentJdbcStore.getById(id));
  }

  @Override
  public Environment getByName(String name) {
    return toModel(environmentJdbcStore.getByName(name));
  }

  @Override
  public Environment getByRegistrationTokenHash(String registrationTokenHash) {
    return toModel(environmentJdbcStore.getByRegistrationTokenHash(registrationTokenHash));
  }

  @Override
  public Environment lockById(UUID id) {
    return toModel(environmentJdbcStore.lockById(id));
  }

  @Override
  public Environment lockForKeyShare(UUID id) {
    return toModel(environmentJdbcStore.lockForKeyShare(id));
  }

  @Override
  public boolean existsByName(String name) {
    return environmentJdbcStore.existsByName(name);
  }

  @Override
  public boolean create(Environment environment) {
    PostgresqlEnvironmentChangeNotifier.requireTransaction();
    boolean created = environmentJdbcStore.insert(toDO(environment)) == 1;
    if (created) {
      notifier.environmentChanged(environment.getId());
    }
    return created;
  }

  @Override
  public boolean updateById(Environment environment, long expectedVersion) {
    PostgresqlEnvironmentChangeNotifier.requireTransaction();
    boolean updated = environmentJdbcStore.updateById(toDO(environment), expectedVersion) == 1;
    if (updated) {
      notifier.environmentChanged(environment.getId());
    }
    return updated;
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    PostgresqlEnvironmentChangeNotifier.requireTransaction();
    boolean deleted = environmentJdbcStore.deleteById(id, expectedVersion) == 1;
    if (deleted) {
      // 连接行随 environment 级联删除，同一 environment 的通知已覆盖级联，不另造连接级来源。
      notifier.environmentChanged(id);
    }
    return deleted;
  }

  @Override
  public boolean saveInstallCode(
      UUID environmentId, long expectedVersion, String codeHash, Instant expiresAt) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(codeHash, "codeHash");
    Objects.requireNonNull(expiresAt, "expiresAt");
    PostgresqlEnvironmentChangeNotifier.requireTransaction();
    return jdbcClient
            .sql(SAVE_INSTALL_CODE_SQL)
            .param("environmentId", environmentId)
            .param("expectedVersion", expectedVersion)
            .param("codeHash", codeHash)
            .param("expiresAt", expiresAt.toString())
            .update()
        == 1;
  }

  @Override
  public boolean redeemInstallCode(
      UUID environmentId, long expectedVersion, String codeHash, String registrationTokenHash) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(codeHash, "codeHash");
    Objects.requireNonNull(registrationTokenHash, "registrationTokenHash");
    PostgresqlEnvironmentChangeNotifier.requireTransaction();
    boolean redeemed =
        jdbcClient
                .sql(REDEEM_INSTALL_CODE_SQL)
                .param("environmentId", environmentId)
                .param("expectedVersion", expectedVersion)
                .param("codeHash", codeHash)
                .param("registrationTokenHash", registrationTokenHash)
                .update()
            == 1;
    if (redeemed) {
      notifier.environmentChanged(environmentId);
    }
    return redeemed;
  }

  private EnvironmentDO toDO(Environment model) {
    if (model == null) {
      return null;
    }
    EnvironmentDO target = new EnvironmentDO();
    target.setId(model.getId());
    target.setName(model.getName());
    target.setRegistrationTokenHash(model.getRegistrationTokenHash());
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
    target.setRegistrationTokenHash(row.getRegistrationTokenHash());
    target.setInstallConfig(EnvironmentInstallConfigs.read(row.getInstallConfigJson()));
    target.setVersion(row.getVersion());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }
}
