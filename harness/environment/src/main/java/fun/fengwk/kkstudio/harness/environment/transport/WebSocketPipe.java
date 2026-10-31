package fun.fengwk.kkstudio.harness.environment.transport;

import org.eclipse.jetty.util.Callback;

import java.net.SocketAddress;
import java.nio.ByteBuffer;

/**
 * 底层 WebSocket 双向通道抽象。
 *
 * <p>只传输二进制消息。发送以回调驱动，接收通过显式 demand 维持背压。
 */
public interface WebSocketPipe {

  /**
   * 发送一条二进制消息。
   *
   * @param payload 待发送载荷
   * @param callback 发送完成回调
   */
  void sendBinary(ByteBuffer payload, Callback callback);

  /** 需求下一条入站消息。 */
  void demand();

  /**
   * 关闭底层 WebSocket 连接。
   *
   * @param statusCode 关闭状态码
   * @param reason 关闭原因
   */
  void close(int statusCode, String reason);

  /** 获取本地网络地址。 */
  SocketAddress getLocalSocketAddress();

  /** 获取远端网络地址。 */
  SocketAddress getRemoteSocketAddress();
}
