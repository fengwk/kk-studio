package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code plugin_credential} 的原子 SQL 契约：一行一凭据，写入必须是单条条件语句，并发靠 CAS version、lease token 与认证身份（代际 +
 * 上下文）围栏。
 *
 * <p>没有无条件 upsert：整行写入只通过 {@link #insert(PluginCredentialRow)}（要求当前无行）与 {@link
 * #replace(PluginCredentialRow, UUID, String, long)}（要求当前行匹配认证代际、上下文指纹与 version）两条条件路径，因此旧引用绝不可能
 * 「复活」一行已被替换或解除的凭据。所有 finalize / release / reject 都要求同时匹配 lease token、version
 * 与认证身份，因此迟到或来自另一节点的写入只能 失败，不能覆盖新凭据、新状态、新上下文或断连重建后的新代际行。
 */
public interface PluginCredentialRepository {

  /** 读取权威行；不存在时返回空。 */
  Optional<PluginCredentialRow> find(String pluginId);

  /** 条件插入整行：当前已存在该 pluginId 的行时不写入并返回 false。 */
  boolean insert(PluginCredentialRow row);

  /**
   * 条件整行替换：只有当前行的认证代际、上下文指纹与 version 三者都匹配时才覆盖载荷、元数据与 lease，并把 {@code version + 1}。
   *
   * @return 是否实际替换了该行
   */
  boolean replace(
      PluginCredentialRow row,
      UUID expectedAuthGeneration,
      String expectedContextFingerprint,
      long expectedVersion);

  /**
   * 条件删除：只有当前行的认证代际、上下文指纹与 version 三者都匹配时才删除。
   *
   * @return 是否实际删除了一行
   */
  boolean delete(
      String pluginId,
      UUID expectedAuthGeneration,
      String expectedContextFingerprint,
      long expectedVersion);

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
   * <p>静态密钥行没有 {@code next_refresh_at}，因此天然不会被领取。{@code SKIP LOCKED} 保证多节点并发扫描不会互相阻塞或重复领取。
   */
  List<PluginCredentialRow> claimDue(
      List<String> pluginIds, Instant now, Instant leaseUntil, String leaseToken, int limit);

  /** 成功终结：替换密文与时间、回到 {@code CONNECTED}，要求认证身份、lease token 与 version 都匹配。 */
  boolean finalizeSuccess(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      byte[] encryptedPayload,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt,
      Instant refreshedAt,
      Instant now);

  /** 失败终结：写入终态、下一刷新时刻与有界去敏错误，清空 lease，要求认证身份、lease token 与 version 都匹配。 */
  boolean finalizeFailure(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      PluginCredentialStatus status,
      Instant nextRefreshAt,
      String error,
      Instant now);

  /** 释放 lease 且不改状态：无法解析凭据或 Plugin 无刷新能力时使用，要求认证身份、lease token 与 version 都匹配。 */
  boolean releaseLease(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      Instant now);

  /**
   * 当前 lease 是否仍由该 token 与 version 持有、认证身份一致且截止时刻晚于 {@code now}。
   *
   * <p>外部刷新只能在这个条件成立时发出：lease 过期、被另一节点收敛、重新登录替换或断连后被同版本新代际行取代后都必须返回 false。
   */
  boolean ownsUnexpiredLease(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      Instant now);

  /**
   * 已安装 Plugin 中最早的可调度刷新时刻。
   *
   * <p>只看 {@code CONNECTED} / {@code REFRESH_FAILED} 且没有未过期 lease 的行；{@code now} 之前的时刻原样返回，表示已经到期。
   */
  Optional<Instant> earliestRefreshAt(List<String> pluginIds, Instant now);

  /**
   * 调用期认证拒绝只作废本次已用凭据：认证代际与上下文指纹匹配，且 version 或原始密文匹配才把该行置为 {@code REAUTH_REQUIRED}。
   *
   * <p>并发重新登录写入的新 version、新密文与新代际都不会命中；断连后被同 version 的新代际行取代也不会命中，因此不会误杀新 token。
   *
   * @return 是否实际作废了当前行
   */
  boolean rejectUsedCredential(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      long version,
      byte[] encryptedPayload,
      String error,
      Instant now);
}
