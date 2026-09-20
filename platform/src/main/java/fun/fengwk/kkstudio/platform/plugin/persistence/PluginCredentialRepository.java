package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code plugin_credential} 的原子 SQL 契约：一行一凭据，写入必须是单条条件语句，并发靠 CAS version 与 lease token 围栏。
 *
 * <p>所有 finalize / release 都要求同时匹配 lease token 与 version，因此迟到或来自另一节点的写入只能失败，不能覆盖新凭据或新状态。
 */
public interface PluginCredentialRepository {

  /** 读取权威行；不存在时返回空。 */
  Optional<PluginCredentialRow> find(String pluginId);

  /**
   * 写入新凭据：行不存在则插入 {@code version = 0}，已存在则在同一条语句内覆盖载荷与元数据、清空 lease 并把 {@code version + 1}。
   *
   * <p>重新登录总是整体替换旧凭据，因此不传 version：用户显式认证的优先级高于任何在途刷新。
   */
  boolean upsert(PluginCredentialRow row);

  /** 断连：删除该 Plugin 的凭据行；返回是否真的删除了一行。 */
  boolean delete(String pluginId);

  /**
   * 把已经过期的 in-flight lease 原子收敛为 {@code REFRESH_UNCERTAIN} 并清空 lease。
   *
   * <p>过期 lease 表示持有节点在外部请求前后崩溃，没有任何节点能证明请求是否已经发出，因此这些行既不能重新 claim 也不能重放， 只能永久收敛为结果未知。
   *
   * @return 收敛的行数
   */
  int markExpiredLeasesUncertain(List<String> pluginIds, Instant now, String error);

  /**
   * 在短事务内领取到期行：只领取给定已安装 Plugin、状态为 {@code CONNECTED / REFRESH_FAILED}、{@code next_refresh_at} 已到且
   * lease 为空或已过期的行，并写入新 lease token 与 {@code version + 1}。
   *
   * <p>{@code SKIP LOCKED} 保证多节点并发扫描不会互相阻塞或重复领取。
   */
  List<PluginCredentialRow> claimDue(
      List<String> pluginIds, Instant now, Instant leaseUntil, String leaseToken, int limit);

  /** 成功终结：替换密文与时间、回到 {@code CONNECTED}，要求 lease token 与 version 都匹配。 */
  boolean finalizeSuccess(
      String pluginId,
      String leaseToken,
      long version,
      byte[] encryptedPayload,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt,
      Instant refreshedAt,
      Instant now);

  /** 失败终结：写入终态、下一刷新时刻与有界去敏错误，清空 lease，要求 lease token 与 version 都匹配。 */
  boolean finalizeFailure(
      String pluginId,
      String leaseToken,
      long version,
      PluginCredentialStatus status,
      Instant nextRefreshAt,
      String error,
      Instant now);

  /** 释放 lease 且不改状态：无法解析凭据或 Plugin 无刷新能力时使用，要求 lease token 与 version 都匹配。 */
  boolean releaseLease(String pluginId, String leaseToken, long version, Instant now);
}
