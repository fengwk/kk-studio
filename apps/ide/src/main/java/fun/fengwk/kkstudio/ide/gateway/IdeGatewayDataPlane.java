package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnels;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelHttpRequest;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * IDE 数据面网关核心：负责同源校验、路由调度、本地隧道交付与远程单跳转发。
 *
 * <p>owner 节点直接通过 {@link EnvironmentTunnel} 交付流量给 Daemon；非 owner 节点透传至 owner。
 */
@Slf4j
@Component
public class IdeGatewayDataPlane {

  private final ClusterNodeRegistry clusterNodeRegistry;
  private final EnvironmentTunnels environmentTunnels;
  private final IdeDataViewAuthorizer viewAuthorizer;
  private final IdeBrowserSessionHandler browserSessionHandler;
  private final IdeRemoteDataPlaneForwarder remoteForwarder;

  public IdeGatewayDataPlane(
      ClusterNodeRegistry clusterNodeRegistry,
      EnvironmentTunnels environmentTunnels,
      IdeDataViewAuthorizer viewAuthorizer,
      IdeBrowserSessionHandler browserSessionHandler,
      IdeRemoteDataPlaneForwarder remoteForwarder) {
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.environmentTunnels = Objects.requireNonNull(environmentTunnels, "environmentTunnels");
    this.viewAuthorizer = Objects.requireNonNull(viewAuthorizer, "viewAuthorizer");
    this.browserSessionHandler =
        Objects.requireNonNull(browserSessionHandler, "browserSessionHandler");
    this.remoteForwarder = Objects.requireNonNull(remoteForwarder, "remoteForwarder");
  }

  /**
   * 处理来自浏览器的 IDE 入口流量。
   *
   * @param rawPathAndQuery view 根路径后的相对路径（含 query），以 {@code /} 开头
   */
  public void handleBrowserTraffic(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");

    if (isExactEndpoint(rawPathAndQuery, IdeGatewayConstants.BOOTSTRAP_ENDPOINT)) {
      handleBootstrap(environmentId, viewId, rawPathAndQuery, request, response);
      return;
    }
    if (isExactEndpoint(rawPathAndQuery, IdeGatewayConstants.SESSION_ENDPOINT)) {
      handleSession(environmentId, viewId, rawPathAndQuery, request, response);
      return;
    }

    boolean isWs = IdeOriginPolicy.isWebSocketUpgrade(request);
    if (isWs
        ? !IdeOriginPolicy.webSocketHandshakeAllowed(request)
        : !IdeOriginPolicy.httpRequestAllowed(request)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    if (!IdeDataPaths.isSafeViewPath(rawPathAndQuery)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_REQUEST);
      return;
    }

    Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    if (route.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }

    if (!route.get().local()) {
      if (IdeOriginPolicy.isWebSocketUpgrade(request)) {
        remoteForwarder.forwardWebSocket(
            request, response, route.get(), environmentId, viewId, rawPathAndQuery);
      } else {
        remoteForwarder.forwardDataHttp(
            request, response, route.get(), environmentId, viewId, rawPathAndQuery);
      }
      return;
    }

    executeLocal(environmentId, viewId, rawPathAndQuery, request, response);
  }

  /** 处理 owner 内部数据面请求（已校验 HMAC，来源为同集群其它节点）。 */
  public void handleInternalDataPlane(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");

    if (isExactEndpoint(rawPathAndQuery, IdeGatewayConstants.BOOTSTRAP_ENDPOINT)) {
      browserSessionHandler.handleBootstrap(environmentId, viewId, request, response);
      return;
    }
    if (isExactEndpoint(rawPathAndQuery, IdeGatewayConstants.SESSION_ENDPOINT)) {
      browserSessionHandler.handleSession(environmentId, viewId, request, response);
      return;
    }

    if (!IdeDataPaths.isSafeViewPath(rawPathAndQuery)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_REQUEST);
      return;
    }

    executeLocal(environmentId, viewId, rawPathAndQuery, request, response);
  }

  private void executeLocal(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Optional<IdeDataViewAuthorizer.IdeDataViewAccess> access =
        viewAuthorizer.authorize(environmentId, viewId, request.getHeader("Cookie"));
    if (access.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    if (!workspaceQueryAllowed(access.get(), request)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    Optional<EnvironmentTunnel> tunnel = environmentTunnels.local(environmentId);
    if (tunnel.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!tunnel.get().identity().equals(access.get().controlIdentity())) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_CONFLICT);
      return;
    }

    boolean isWs = IdeOriginPolicy.isWebSocketUpgrade(request);
    String daemonPath =
        IdeDataPaths.daemonPath(viewId, access.get().bindingEpoch(), rawPathAndQuery);
    Map<String, List<String>> forwardHeaders =
        IdeDataHeaderPolicy.forwardRequestHeaders(request, isWs);

    if (isWs) {
      dispatchLocalWebSocket(
          tunnel.get(), daemonPath, forwardHeaders, request, response, environmentId, viewId);
    } else {
      dispatchLocalHttp(
          tunnel.get(), daemonPath, forwardHeaders, request, response, environmentId, viewId);
    }
  }

  private void dispatchLocalHttp(
      EnvironmentTunnel tunnel,
      String daemonPath,
      Map<String, List<String>> forwardHeaders,
      HttpServletRequest request,
      HttpServletResponse response,
      EnvironmentId environmentId,
      UUID viewId)
      throws IOException {
    TunnelHttpRequest tunnelRequest =
        new TunnelHttpRequest(request.getMethod(), daemonPath, forwardHeaders);
    TunnelExchange exchange;
    try {
      exchange = tunnel.openHttp(tunnelRequest);
    } catch (IllegalStateException error) {
      log.info(
          "local tunnel open failed; phase=LOCAL_TUNNEL_OPEN_HTTP; environmentId={}; viewId={}; exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    try {
      boolean hasBody =
          request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
      Thread.ofVirtual()
          .name("ide-local-http-pump")
          .start(
              () -> {
                try {
                  if (hasBody) {
                    try (InputStream in = request.getInputStream()) {
                      byte[] buffer = new byte[16 * 1024];
                      int read;
                      while ((read = in.read(buffer)) >= 0) {
                        if (read > 0) {
                          ByteBuffer bb = ByteBuffer.wrap(buffer, 0, read);
                          exchange.stream().write(bb).toCompletableFuture().join();
                        }
                      }
                    }
                  }
                  exchange.stream().endOutput().toCompletableFuture().join();
                } catch (Exception error) {
                  log.debug("ide local http pump failed", error);
                  try {
                    exchange.close();
                  } catch (Exception closeErr) {
                    log.debug("failed to close exchange after pump failure", closeErr);
                  }
                }
              });

      TunnelExchange.Response tunnelResponse;
      try {
        tunnelResponse =
            exchange
                .responseHeaders()
                .toCompletableFuture()
                .get(IdeGatewayConstants.DEFAULT_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      } catch (Exception error) {
        log.info(
            "local tunnel response headers failed; phase=LOCAL_TUNNEL_RESPONSE_HEADERS; environmentId={}; viewId={}; exception={}",
            environmentId,
            viewId,
            error.getClass().getSimpleName());
        if (!response.isCommitted()) {
          IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
        }
        return;
      }

      IdeDataHeaderPolicy.applyResponseHeaders(
          tunnelResponse.status(),
          tunnelResponse.headers(),
          request,
          response,
          environmentId,
          viewId,
          false);

      if ("HEAD".equalsIgnoreCase(request.getMethod())
          || tunnelResponse.status() == 204
          || tunnelResponse.status() == 304) {
        return;
      }

      try (OutputStream out = response.getOutputStream()) {
        while (true) {
          ByteBuffer chunk =
              exchange.stream()
                  .read()
                  .toCompletableFuture()
                  .get(IdeGatewayConstants.DEFAULT_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
          if (chunk == null) {
            break;
          }
          if (chunk.hasRemaining()) {
            byte[] bytes = new byte[chunk.remaining()];
            chunk.get(bytes);
            out.write(bytes);
            out.flush();
          }
        }
        out.flush();
      }
    } catch (Exception error) {
      log.info(
          "local tunnel http transfer failed; phase=LOCAL_TUNNEL_TRANSFER; environmentId={}; viewId={}; exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      if (!response.isCommitted()) {
        IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
      }
    } finally {
      try {
        exchange.close();
      } catch (Exception closeErr) {
        log.debug("failed to close local tunnel exchange", closeErr);
      }
    }
  }

  private void dispatchLocalWebSocket(
      EnvironmentTunnel tunnel,
      String daemonPath,
      Map<String, List<String>> forwardHeaders,
      HttpServletRequest request,
      HttpServletResponse response,
      EnvironmentId environmentId,
      UUID viewId)
      throws IOException {
    TunnelHttpRequest tunnelRequest = new TunnelHttpRequest("GET", daemonPath, forwardHeaders);
    TunnelExchange exchange;
    try {
      exchange = tunnel.openWebSocket(tunnelRequest);
    } catch (IllegalStateException error) {
      log.info(
          "local tunnel open ws failed; phase=LOCAL_TUNNEL_OPEN_WS; environmentId={}; viewId={}; exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    TunnelExchange.Response tunnelResponse;
    try {
      tunnelResponse =
          exchange
              .responseHeaders()
              .toCompletableFuture()
              .get(IdeGatewayConstants.DEFAULT_CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (Exception error) {
      try {
        exchange.close();
      } catch (Exception closeErr) {
        log.debug("failed to close ws exchange after header error", closeErr);
      }
      log.info(
          "local tunnel ws headers failed; phase=LOCAL_TUNNEL_WS_HEADERS; environmentId={}; viewId={}; exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      if (!response.isCommitted()) {
        IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
      }
      return;
    }

    if (tunnelResponse.status() == 200) {
      try {
        JettyConnectionHandover.handover(request, 101, tunnelResponse.headers(), exchange.stream());
      } catch (Exception error) {
        log.info(
            "local tunnel ws handover failed; phase=LOCAL_TUNNEL_WS_HANDOVER; environmentId={}; viewId={}; exception={}",
            environmentId,
            viewId,
            error.getClass().getSimpleName());
        try {
          exchange.close();
        } catch (Exception closeErr) {
          log.debug("failed to close ws exchange after handover failure", closeErr);
        }
        if (!response.isCommitted()) {
          IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
        }
      }
    } else {
      try {
        exchange.close();
      } catch (Exception closeErr) {
        log.debug("failed to close ws exchange on non-200 response", closeErr);
      }
      IdeDataHeaderPolicy.sendError(response, tunnelResponse.status());
    }
  }

  private void handleBootstrap(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    if (route.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!route.get().local()) {
      remoteForwarder.forwardManagementHttp(
          request, response, route.get(), environmentId, viewId, rawPathAndQuery);
      return;
    }
    browserSessionHandler.handleBootstrap(environmentId, viewId, request, response);
  }

  private void handleSession(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    if (route.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!route.get().local()) {
      remoteForwarder.forwardManagementHttp(
          request, response, route.get(), environmentId, viewId, rawPathAndQuery);
      return;
    }
    browserSessionHandler.handleSession(environmentId, viewId, request, response);
  }

  private static boolean workspaceQueryAllowed(
      IdeDataViewAuthorizer.IdeDataViewAccess access, HttpServletRequest request) {
    for (String name : List.of("workspace", "folder")) {
      String value = request.getParameter(name);
      if (value != null && !access.authorizedWorkspaces().contains(value)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isExactEndpoint(String rawPathAndQuery, String endpoint) {
    if (rawPathAndQuery == null) {
      return false;
    }
    int question = rawPathAndQuery.indexOf('?');
    String path = question < 0 ? rawPathAndQuery : rawPathAndQuery.substring(0, question);
    return endpoint.equals(path);
  }
}
