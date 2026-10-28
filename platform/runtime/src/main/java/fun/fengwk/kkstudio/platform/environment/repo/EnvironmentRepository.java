package fun.fengwk.kkstudio.platform.environment.repo;

import fun.fengwk.kkstudio.platform.environment.service.model.Environment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 稳定 Environment Card 仓库。 */
public interface EnvironmentRepository {

  List<Environment> listNewestFirst();

  /** 按 {@code name asc} 列出全部 Environment（含 registrationTokenHash），用于一次性导出快照。 */
  List<Environment> listAll();

  Environment getById(UUID id);

  Environment getByName(String name);

  Environment getByRegistrationTokenHash(String registrationTokenHash);

  Environment lockById(UUID id);

  Environment lockForKeyShare(UUID id);

  boolean existsByName(String name);

  boolean create(Environment environment);

  /** CAS 原子更新 registrationTokenHash 与 installConfig；name 是不可变身份，绝不参与更新。 */
  boolean updateById(Environment environment, long expectedVersion);

  boolean deleteById(UUID id, long expectedVersion);

  /**
   * 保存或覆盖 Environment 行上的临时安装 code；不修改安装配置，不递增 CAS 版本。
   *
   * <p>调用方必须在锁定同一 Environment 行的事务中执行，并通过 {@code expectedVersion} 证明签发基于当前配置版本。
   *
   * @param environmentId 目标 Environment ID
   * @param expectedVersion 签发时校验过的 Environment 版本
   * @param codeHash 临时安装 code 的 SHA-256 小写十六进制摘要
   * @param expiresAt 过期时间戳
   * @return 目标行存在且版本仍匹配时返回 true
   */
  boolean saveInstallCode(
      UUID environmentId, long expectedVersion, String codeHash, Instant expiresAt);

  /**
   * 原子兑换安装 code：在数据库时间下严格校验摘要与有效期后，清空 install_code、轮换注册凭证并递增版本。
   *
   * <p>调用方必须在锁定同一 Environment 行的事务中执行；摘要不匹配、已过期或版本不匹配时返回 false 且不写入任何字段。
   *
   * @param environmentId 目标 Environment ID
   * @param expectedVersion 兑换时锁定行给出的 Environment 版本
   * @param codeHash 临时安装 code 的 SHA-256 小写十六进制摘要
   * @param registrationTokenHash 新注册凭证的 SHA-256 小写十六进制摘要
   * @return 成功兑换返回 true，否则返回 false
   */
  boolean redeemInstallCode(
      UUID environmentId, long expectedVersion, String codeHash, String registrationTokenHash);
}
