package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 非 owner 节点把 Daemon 反向数据连接单跳转发给 READY owner 的客户端。
 *
 * <p>Node 只连接受控的 Studio public stream endpoint（可能是负载均衡），落在非 owner 时本类用内部 HMAC 认证头向 owner 的 {@code
 * /internal/environment-ide/{env}/reverse?ticket=...} 发起一次 WS 连接，并在 Node 侧与 owner 侧之间做有界帧透传。转发不解释
 * ticket、不改写帧、不产生额外 ACK；owner 未知/转发失败一律 fail-closed，绝不回退到本地或其它节点。
 */
@Slf4j
@Component
public final class IdeOwnerStreamForwarder {

  private final ClusterInternalClient clusterInternalClient;
  private final WebSocketClient webSocketClient;

  @Autowired
  public IdeOwnerStreamForwarder(
      ClusterInternalClient clusterInternalClient, WebSocketClient webSocketClient) {
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
  }

  /**
   * 把 Node 侧连接转发到 owner 的内部反向端点。
   *
   * @param nodePeer 已包装的 Node 侧连接（入站帧由端点入队）
   */
  void forward(
      ClusterIdeOwnerRoute owner,
      EnvironmentId environmentId,
      String ticket,
      IdeStreamPeer nodePeer) {
    String canonicalPath = ClusterInternalPaths.reversePath(environmentId, ticket);
    Map<String, String> authHeaders =
        clusterInternalClient.signHeaders("GET", canonicalPath, owner);
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    authHeaders.forEach(headers::add);
    URI target;
    try {
      target = internalWebSocketUri(owner.internalUrl(), canonicalPath);
    } catch (IllegalArgumentException error) {
      log.debug("cannot forward ide reverse stream: {}", error.getMessage());
      nodePeer.close();
      return;
    }
    AtomicReference<IdeStreamPeer> ownerPeer = new AtomicReference<>();
    CompletableFuture<WebSocketSession> pending =
        webSocketClient.execute(
            new AbstractWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) {
                WebSocketIdeStreamPeer peer = new WebSocketIdeStreamPeer(session);
                ownerPeer.set(peer);
                new IdeStreamFrameBridge(nodePeer, peer).start();
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                offer(ownerPeer, new IdeStreamPeer.Inbound.Text(message.getPayload()));
              }

              @Override
              protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                ByteBuffer payload = message.getPayload();
                byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                offer(ownerPeer, new IdeStreamPeer.Inbound.Binary(bytes));
              }

              @Override
              public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                detach(ownerPeer);
              }

              @Override
              public void handleTransportError(WebSocketSession session, Throwable exception) {
                detach(ownerPeer);
              }
            },
            headers,
            target);
    pending.whenComplete(
        (session, error) -> {
          if (error != null) {
            log.debug("ide reverse forward to owner failed: {}", error.getMessage());
            nodePeer.close();
          }
        });
  }

  private static void offer(AtomicReference<IdeStreamPeer> ownerPeer, IdeStreamPeer.Inbound frame) {
    IdeStreamPeer peer = ownerPeer.get();
    if (peer instanceof WebSocketIdeStreamPeer webSocketPeer && !webSocketPeer.offer(frame)) {
      // 超出有界入站预算：fail-closed 关闭连接，绝不无界缓冲。
      webSocketPeer.close();
    }
  }

  private static void detach(AtomicReference<IdeStreamPeer> ownerPeer) {
    IdeStreamPeer peer = ownerPeer.get();
    if (peer instanceof WebSocketIdeStreamPeer webSocketPeer) {
      webSocketPeer.onTransportClosed();
    }
  }

  /** 由内部 origin 与 canonical path 构造内部反向 WS 地址；只接受裸 http(s) origin。 */
  private static URI internalWebSocketUri(URI internalUrl, String canonicalPath) {
    if (internalUrl == null) {
      throw new IllegalArgumentException("owner route has no internal origin");
    }
    String scheme = internalUrl.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException("owner internal origin has no scheme");
    }
    String webSocketScheme =
        switch (scheme.toLowerCase(Locale.ROOT)) {
          case "http" -> "ws";
          case "https" -> "wss";
          default -> throw new IllegalArgumentException("owner internal origin must be http(s)");
        };
    if (internalUrl.getHost() == null
        || internalUrl.getUserInfo() != null
        || internalUrl.getQuery() != null
        || internalUrl.getFragment() != null) {
      throw new IllegalArgumentException("owner internal origin must be a bare origin");
    }
    return URI.create(webSocketScheme + "://" + internalUrl.getAuthority() + canonicalPath);
  }
}
