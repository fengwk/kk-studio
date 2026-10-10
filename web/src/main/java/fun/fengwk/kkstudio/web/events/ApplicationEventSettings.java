package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.share.notification.NotificationLimits;

/**
 * 浏览器事件通道的软策略配置。
 *
 * <p>由 {@link ApplicationEventConfiguration} 用一次数据库 SystemSettings.Advanced 快照构造：queueCapacity 对应
 * {@code applicationEventQueueCapacity}（每连接出站待发逻辑包数上限，同时仍是 Hub 单订阅/建立期缓冲上限），maxBytes 对应 {@code
 * applicationEventMaxBytes}（每连接 pending 的整包 UTF-8 字节上限，含在途整包），sendTimeoutMillis 对应 {@code
 * applicationEventSendTimeoutMillis}（单次 AsyncRemote 发送超时），heartbeatIntervalMillis 对应 {@code
 * applicationEventHeartbeatIntervalMillis}。
 *
 * <p>逻辑整包上限固定为共享 carrier 的 {@link NotificationLimits#DEFAULT_MAX_MESSAGE_BYTES}（8 MiB）；pending
 * 预算必须至少容纳一个整包，因此 maxBytes 下界即 8 MiB，配置值不会被静默抬高。
 *
 * @param queueCapacity 每连接出站待发逻辑包数上限（>0）
 * @param maxBytes 每连接 pending 整包 UTF-8 字节上限（≥ 8 MiB）
 * @param sendTimeoutMillis AsyncRemote 单次发送超时（>0）
 * @param heartbeatIntervalMillis server 端 heartbeat 间隔（>0）
 */
public record ApplicationEventSettings(
    int queueCapacity, long maxBytes, long sendTimeoutMillis, long heartbeatIntervalMillis) {

  public ApplicationEventSettings {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("applicationEvent queueCapacity must be positive");
    }
    if (maxBytes < NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES) {
      throw new IllegalArgumentException(
          "applicationEvent maxBytes must be at least "
              + NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES);
    }
    if (sendTimeoutMillis <= 0) {
      throw new IllegalArgumentException("applicationEvent sendTimeoutMillis must be positive");
    }
    if (heartbeatIntervalMillis <= 0) {
      throw new IllegalArgumentException(
          "applicationEvent heartbeatIntervalMillis must be positive");
    }
  }
}
