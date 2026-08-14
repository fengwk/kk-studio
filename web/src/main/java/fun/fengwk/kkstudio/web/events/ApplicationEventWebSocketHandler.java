package fun.fengwk.kkstudio.web.events;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览器事件通道 {@code /api/events/v1} 的 Spring WebSocket 适配器。
 *
 * <p>连接建立时解包 {@link NativeWebSocketSession} 获得 {@link jakarta.websocket.Session}，正文全部经 {@link
 * AsyncTextSender}（jakarta AsyncRemote）异步串行发送，不使用同步 Spring sendMessage。会话状态（订阅表、 发送队列）按连接维护：重复
 * subscribe 幂等、断线释放全部订阅；非法帧或资源不存在时发送 error 帧并关闭连接。
 */
@Component
public final class ApplicationEventWebSocketHandler extends TextWebSocketHandler {

  public static final String PATH = "/api/events/v1";

  private final ApplicationEventHub hub;
  private final EventFrameCodec codec;
  private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();

  public ApplicationEventWebSocketHandler(ApplicationEventHub hub, EventFrameCodec codec) {
    this.hub = Objects.requireNonNull(hub, "hub");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    Session nativeSession = requireNativeSession(session);
    connections.put(
        session.getId(), new ConnectionState(hub, codec, new AsyncTextSender(nativeSession)));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    ConnectionState state = connections.get(session.getId());
    if (state != null) {
      state.handle(message.getPayload());
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    close(session.getId());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    close(session.getId());
  }

  private void close(String sessionId) {
    ConnectionState state = connections.remove(sessionId);
    if (state != null) {
      state.close();
    }
  }

  private static Session requireNativeSession(WebSocketSession session) {
    if (session instanceof NativeWebSocketSession nativeWebSocketSession) {
      Object nativeSession = nativeWebSocketSession.getNativeSession();
      if (nativeSession instanceof Session jakartaSession) {
        return jakartaSession;
      }
    }
    throw new IllegalStateException(
        "event channel requires a servlet NativeWebSocketSession with a jakarta Session");
  }

  /** 单连接的协议状态：订阅表 + 发送队列。 */
  private static final class ConnectionState {

    private final ApplicationEventHub hub;
    private final EventFrameCodec codec;
    private final AsyncTextSender sender;
    private final Map<ResourceKey, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Object closeLock = new Object();
    private boolean closed;

    private ConnectionState(
        ApplicationEventHub hub, EventFrameCodec codec, AsyncTextSender sender) {
      this.hub = hub;
      this.codec = codec;
      this.sender = sender;
    }

    private void handle(String payload) {
      EventFrameCodec.ClientFrame frame;
      try {
        frame = codec.decode(payload);
      } catch (RuntimeException error) {
        fail("invalid frame: " + error.getMessage(), CloseReason.CloseCodes.VIOLATED_POLICY);
        return;
      }
      switch (frame.type()) {
        case SUBSCRIBE -> subscribe(frame.resource());
        case UNSUBSCRIBE -> unsubscribe(frame.resource());
      }
    }

    private void subscribe(ResourceKey resource) {
      if (subscriptions.containsKey(resource)) {
        return; // 重复 subscribe 幂等
      }
      Subscription subscription;
      try {
        subscription =
            hub.subscribe(resource, signal -> sender.enqueue(codec.event(resource, signal)));
      } catch (IllegalArgumentException error) {
        fail("subscribe failed: " + error.getMessage(), CloseReason.CloseCodes.VIOLATED_POLICY);
        return;
      }
      sender.enqueue(codec.subscribed(resource, subscription.cursor()));
      subscription.activate();
      subscriptions.put(resource, subscription);
    }

    private void unsubscribe(ResourceKey resource) {
      Subscription subscription = subscriptions.remove(resource);
      if (subscription != null) {
        subscription.close();
      }
    }

    /** 发送 error 帧并在其出队后关闭连接。 */
    private void fail(String message, CloseReason.CloseCodes code) {
      sender.fail(codec.error(message), code);
    }

    private void close() {
      synchronized (closeLock) {
        if (closed) {
          return;
        }
        closed = true;
      }
      for (Subscription subscription : subscriptions.values()) {
        subscription.close();
      }
      subscriptions.clear();
    }
  }
}
