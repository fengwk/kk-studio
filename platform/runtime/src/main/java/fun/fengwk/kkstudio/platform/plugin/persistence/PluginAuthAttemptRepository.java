package fun.fengwk.kkstudio.platform.plugin.persistence;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code plugin_auth_attempt} 的原子 SQL 契约：每个 Plugin 至多一个 pending attempt，消费必须是单条条件语句。
 *
 * <p>{@link #replace(PluginAuthAttemptRow)} 是唯一的写入路径，新的 prepare 直接替换旧 attempt，因此旧 attemptId 立即失效；
 * {@link #consume(String, String, Instant, long)} 是唯一的消费路径：它必须在同一条语句里同时要求摘要命中、未过期、全局 epoch
 * 仍等于调用方读到的值，且该 Plugin 在当前 manifest 中的代际/上下文指纹/配置修订仍与 attempt 逐字段相等。因此重放、过期、已被取代的 attemptId，以及任何在
 * prepare 之后发生的提交（配置变更、登录完成、断连撤销）都只能得到空结果，「先检查后按 nonce 单独删除」的窗口不存在。
 */
public interface PluginAuthAttemptRepository {

  /** 写入或替换该 Plugin 当前唯一的 pending attempt（要求非空）。 */
  void replace(PluginAuthAttemptRow attempt);

  /** 读取该 Plugin 当前的 pending attempt；不存在时返回空。 */
  Optional<PluginAuthAttemptRow> find(String pluginId);

  /**
   * 原子单次消费：只有摘要命中、{@code expires_at} 严格晚于 {@code now}、全局 epoch 仍等于 {@code expectedEpoch}，且当前
   * manifest 中该 Plugin 的代际/上下文指纹/配置修订与 attempt 逐字段相等时才删除并返回该行。
   *
   * <p>所有条件都在同一条语句里判定，因此 revoked/已变更的坐标绝不会换来一次性回调许可。
   *
   * @param expectedEpoch 调用方读到的权威全局 epoch；其间任何提交都会让 epoch 前进从而使本次消费失败
   * @return 被消费的 attempt；任一条件不满足时为空
   */
  Optional<PluginAuthAttemptRow> consume(
      String pluginId, String nonceHash, Instant now, long expectedEpoch);
}
