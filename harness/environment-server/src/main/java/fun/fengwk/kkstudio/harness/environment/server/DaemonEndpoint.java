package fun.fengwk.kkstudio.harness.environment.server;

/**
 * Environment daemon 连接的服务端窄端口：transport 适配器（如 WebSocket）只依赖本接口。
 *
 * <p>实现负责把物理连接适配为 {@link DaemonChannel} 并投递入站文本帧；协议、lease 与 invocation 状态全部由实现内部拥有， 适配器不得缓存任何会话状态。
 */
public interface DaemonEndpoint {

  /** 在首帧 HELLO 到达前注册一条新打开的连接。 */
  void open(DaemonChannel channel);

  /** 投递一帧入站文本；协议违规由实现负责生成 ERROR 帧并关闭连接。 */
  void receive(String connectionId, String rawMessage);

  /** 丢弃物理连接代际；在途调用保留等待同实例 daemon 重连重放。必须幂等。 */
  void close(String connectionId);
}
