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
   * 保存或覆盖 Environment 的临时安装 code 记录（upsert）。
   *
   * @param environmentId 目标 Environment ID
   * @param codeHash 临时安装 code 的 SHA-256 小写十六进制摘要
   * @param registrationTokenHash 签发时对应的 registrationTokenHash
   * @param expiresAt 过期时间戳
   */
  void saveInstallCode(
      UUID environmentId, String codeHash, String registrationTokenHash, Instant expiresAt);

  /**
   * 原子核销临时安装 code：仅当目标环境存在、codeHash 与 registrationTokenHash 完全一致且尚未过期（expires_at > now）时删除并返回 true。
   *
   * @param environmentId 目标 Environment ID
   * @param codeHash 临时安装 code 的 SHA-256 小写十六进制摘要
   * @param registrationTokenHash 签发时对应的 registrationTokenHash
   * @param now 当前时间戳，必须严格小于 expires_at
   * @return 若成功核销并删除返回 true，否则返回 false
   */
  boolean consumeInstallCode(
      UUID environmentId, String codeHash, String registrationTokenHash, Instant now);
}
