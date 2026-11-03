package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelStream;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Jetty EE11 公开连接交接辅助工具：将当前 HTTP 请求升级为由 {@link JettyUpgradeConnection} 接管的长连接。 */
public final class JettyConnectionHandover {

  private JettyConnectionHandover() {}

  public static void handover(
      HttpServletRequest request,
      int status,
      Map<String, List<String>> headers,
      TunnelStream tunnel) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(tunnel, "tunnel");
    ServletContextRequest servletContextRequest =
        ServletContextRequest.getServletContextRequest(request);
    if (servletContextRequest == null || servletContextRequest.getServletChannel() == null) {
      throw new IllegalStateException("not running inside Jetty EE11 servlet context");
    }
    Request coreRequest = servletContextRequest.getServletChannel().getRequest();
    Response coreResponse = servletContextRequest.getServletChannel().getResponse();
    ConnectionMetaData metadata = coreRequest.getConnectionMetaData();

    JettyUpgradeConnection connection =
        new JettyUpgradeConnection(
            metadata.getConnection().getEndPoint(),
            metadata.getConnector().getServer().getThreadPool(),
            tunnel);
    coreRequest.setAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE, connection);

    coreResponse.setStatus(status);
    if (headers != null) {
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        String name = entry.getKey();
        if (IdeDataHeaderPolicy.isForwardableResponseHeader(name, true)) {
          for (String value : entry.getValue()) {
            coreResponse.getHeaders().add(name, value);
          }
        }
      }
    }
    coreResponse.getHeaders().put("Connection", "Upgrade");
    coreResponse.getHeaders().put("Upgrade", "websocket");
    coreResponse.write(true, null, Callback.from(() -> {}, err -> tunnel.close()));
  }
}
