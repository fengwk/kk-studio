package fun.fengwk.kkstudio.platform.daemon.transport;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Promise;

import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelHttpRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 承载数据面 HTTP 交换与 extended CONNECT WebSocket 隧道的 {@link EnvironmentTunnel} 实现。
 *
 * <p>遵循隧道窄契约：单流每个方向最多一个未完成操作；严格按需需求（未挂起 read 时不主动 demand），杜绝无界预读； 针对流尚未 attach 时的写与半关闭支持按序排队；跳过 1xx
 * 信息响应；EOF 重复返回 {@code null}；写成功推进 position 到 limit。
 */
public class DaemonEnvironmentTunnel implements EnvironmentTunnel {

  private final DaemonConnectionIdentity identity;
  private final Session session;
  private final BooleanSupplier closedCheck;
  private final List<Http2TunnelExchange> activeExchanges = new ArrayList<>();
  private final Object lock = new Object();
  private boolean draining;
  private boolean closed;

  public DaemonEnvironmentTunnel(
      DaemonConnectionIdentity identity, Session session, BooleanSupplier closedCheck) {
    this.identity = Objects.requireNonNull(identity, "identity must not be null");
    this.session = Objects.requireNonNull(session, "session must not be null");
    this.closedCheck = Objects.requireNonNull(closedCheck, "closedCheck must not be null");
  }

  @Override
  public DaemonConnectionIdentity identity() {
    return identity;
  }

  /** 开始排空会话，拒绝新建交换。 */
  public void drain() {
    synchronized (lock) {
      draining = true;
    }
  }

  /** 关闭整个隧道及所有活跃流。 */
  public void onSessionClosed(Throwable failure) {
    List<Http2TunnelExchange> copy;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      copy = new ArrayList<>(activeExchanges);
      activeExchanges.clear();
    }
    Throwable cause = failure != null ? failure : new IOException("tunnel session closed");
    for (Http2TunnelExchange exchange : copy) {
      exchange.failStream(cause);
    }
  }

  private void removeExchange(Http2TunnelExchange exchange) {
    synchronized (lock) {
      activeExchanges.remove(exchange);
    }
  }

  /** 把业务 header 原样铺开成 HTTP/2 字段，保留同名多值顺序。 */
  private static HttpFields.Mutable httpFields(TunnelHttpRequest request) {
    HttpFields.Mutable fields = HttpFields.build();
    request
        .headers()
        .forEach(
            (name, values) -> {
              for (String v : values) {
                fields.add(name, v);
              }
            });
    return fields;
  }

  @Override
  public TunnelExchange openHttp(TunnelHttpRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    checkAvailable();

    MetaData.Request meta =
        new MetaData.Request(
            request.method(),
            HttpURI.from("https://daemon" + request.path()),
            HttpVersion.HTTP_2,
            httpFields(request));

    return createExchange(meta);
  }

  @Override
  public TunnelExchange openWebSocket(TunnelHttpRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!"GET".equals(request.method())) {
      throw new IllegalArgumentException("openWebSocket request method must be GET");
    }
    checkAvailable();

    MetaData.ConnectRequest meta =
        new MetaData.ConnectRequest(
            HttpScheme.HTTPS,
            new HostPortHttpField("daemon"),
            request.path(),
            httpFields(request),
            "websocket");

    return createExchange(meta);
  }

  private void checkAvailable() {
    synchronized (lock) {
      if (closed || draining || closedCheck.getAsBoolean()) {
        throw new IllegalStateException("tunnel session is closed or draining");
      }
    }
  }

  private TunnelExchange createExchange(MetaData.Request meta) {
    Http2TunnelExchange exchange = new Http2TunnelExchange();
    exchange.onFinished(() -> removeExchange(exchange));
    synchronized (lock) {
      activeExchanges.add(exchange);
    }

    session.newStream(
        new HeadersFrame(meta, null, false),
        new Promise<>() {
          @Override
          public void succeeded(Stream stream) {
            exchange.attachStream(stream);
          }

          @Override
          public void failed(Throwable failure) {
            exchange.failStream(failure);
          }
        },
        exchange.listener());

    return exchange;
  }
}
