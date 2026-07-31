package fun.fengwk.kkstudio.harness.daemon.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** 基于 JDK {@link WebSocket} 的生产 transport。 */
public final class JdkWebSocketTransport implements DaemonTransport {

  private final HttpClient httpClient;
  private final URI gatewayUri;

  public JdkWebSocketTransport(URI gatewayUri) {
    this(HttpClient.newHttpClient(), gatewayUri);
  }

  JdkWebSocketTransport(HttpClient httpClient, URI gatewayUri) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.gatewayUri = Objects.requireNonNull(gatewayUri, "gatewayUri");
  }

  @Override
  public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
    return httpClient
        .newWebSocketBuilder()
        .buildAsync(gatewayUri, new ListenerAdapter(listener))
        .thenApply(ConnectionAdapter::new);
  }

  static final class ConnectionAdapter implements DaemonConnection {

    private final WebSocket webSocket;
    private final AtomicBoolean open = new AtomicBoolean(true);

    ConnectionAdapter(WebSocket webSocket) {
      this.webSocket = webSocket;
    }

    @Override
    public CompletionStage<Void> sendText(String message) {
      if (!isOpen()) {
        return CompletableFuture.failedFuture(new IllegalStateException("websocket is closed"));
      }
      return webSocket.sendText(message, true).thenApply(ignored -> null);
    }

    @Override
    public void close() {
      if (open.compareAndSet(true, false)) {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "daemon stopped");
      }
    }

    @Override
    public boolean isOpen() {
      return open.get() && !webSocket.isOutputClosed();
    }
  }

  static final class ListenerAdapter implements WebSocket.Listener {

    private final DaemonTransportListener listener;
    private final StringBuilder text = new StringBuilder();
    private final AtomicBoolean disconnected = new AtomicBoolean();

    ListenerAdapter(DaemonTransportListener listener) {
      this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      text.append(data);
      if (last) {
        String message = text.toString();
        text.setLength(0);
        listener.onMessage(message);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      notifyDisconnected(null);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      notifyDisconnected(error);
    }

    private void notifyDisconnected(Throwable cause) {
      if (disconnected.compareAndSet(false, true)) {
        listener.onDisconnected(cause);
      }
    }
  }
}
