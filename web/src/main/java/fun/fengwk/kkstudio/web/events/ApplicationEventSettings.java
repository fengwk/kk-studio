package fun.fengwk.kkstudio.web.events;

/**
 * 浏览器事件通道的软策略配置。
 *
 * <p>由 {@link ApplicationEventConfiguration} 用一次数据库 SystemSettings.Advanced 快照构造：queueCapacity 对应
 * {@code applicationEventQueueCapacity}（既是每连接发送队列帧容量，也是 Hub 单订阅/建立期缓冲上限），maxBytes 对应 {@code
 * applicationEventMaxBytes}，sendTimeoutMillis 对应 {@code applicationEventSendTimeoutMillis}，
 * heartbeatIntervalMillis 对应 {@code applicationEventHeartbeatIntervalMillis}。
 *
 * @param queueCapacity 每连接发送队列的帧容量（>0）
 * @param maxBytes 每连接待发 UTF-8 字节上限（>0）
 * @param sendTimeoutMillis AsyncRemote 单次发送超时（>0）
 * @param heartbeatIntervalMillis server 端 heartbeat 间隔（>0）
 */
public record ApplicationEventSettings(
    int queueCapacity, long maxBytes, long sendTimeoutMillis, long heartbeatIntervalMillis) {

  public ApplicationEventSettings {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("applicationEvent queueCapacity must be positive");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("applicationEvent maxBytes must be positive");
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
