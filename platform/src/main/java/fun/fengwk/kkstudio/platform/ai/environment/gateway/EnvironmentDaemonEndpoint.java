package fun.fengwk.kkstudio.platform.ai.environment.gateway;

/**
 * Environment daemon 连接的窄 Platform transport 端点。
 *
 * <p>WebSocket 适配器只依赖该接口。协议所有权与持久化 remote tool transport 仍由 Gateway 实现负责。
 */
public interface EnvironmentDaemonEndpoint {

  /** 在首帧 HELLO 到达前注册一条新打开的 transport。 */
  void open(EnvironmentDaemonConnection connection);

  /** 处理一帧入站文本。协议解码/序号校验可能持有连接状态；ToolExecutionListener 与 READY 分发 总是在锁释放后执行。 */
  void receive(String connectionId, String rawMessage);

  /** 丢弃 transport 句柄；活动 remote 按结果不确定（outcome-uncertain）通知。 */
  void close(String connectionId);
}
