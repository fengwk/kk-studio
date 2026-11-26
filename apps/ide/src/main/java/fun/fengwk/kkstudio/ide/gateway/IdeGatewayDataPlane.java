package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioOriginPolicy;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnels;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.platform.daemon.tunnel.TunnelHttpRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
  private final IdeStudioSessionMonitor sessionMonitor;

  public IdeGatewayDataPlane(
      ClusterNodeRegistry clusterNodeRegistry,
      EnvironmentTunnels environmentTunnels,
      IdeDataViewAuthorizer viewAuthorizer,
      IdeBrowserSessionHandler browserSessionHandler,
      IdeRemoteDataPlaneForwarder remoteForwarder,
      IdeStudioSessionMonitor sessionMonitor) {
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.environmentTunnels = Objects.requireNonNull(environmentTunnels, "environmentTunnels");
    this.viewAuthorizer = Objects.requireNonNull(viewAuthorizer, "viewAuthorizer");
    this.browserSessionHandler =
        Objects.requireNonNull(browserSessionHandler, "browserSessionHandler");
    this.remoteForwarder = Objects.requireNonNull(remoteForwarder, "remoteForwarder");
    this.sessionMonitor = Objects.requireNonNull(sessionMonitor, "sessionMonitor");
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

    boolean isWs = StudioOriginPolicy.isWebSocketUpgrade(request);
    if (isWs
        ? !StudioOriginPolicy.webSocketHandshakeAllowed(request)
        : !StudioOriginPolicy.httpRequestAllowed(request)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    if (!IdeDataPaths.isSafeViewPath(rawPathAndQuery)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_REQUEST);
      return;
    }

    Object principalAttribute = request.getAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE);
    if (!(principalAttribute instanceof StudioPrincipal principal)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }

    Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    if (route.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }

    if (isWs) {
      request.setAttribute(
          IdeStudioSessionMonitor.LISTENER_ATTRIBUTE,
          sessionMonitor.listener(StudioBrowserSession.token(request), principal.userId()));
    }
    if (!route.get().local()) {
      if (isWs) {
        remoteForwarder.forwardWebSocket(
            request, response, route.get(), environmentId, viewId, rawPathAndQuery);
      } else {
        remoteForwarder.forwardDataHttp(
            request, response, route.get(), environmentId, viewId, rawPathAndQuery);
      }
      return;
    }

    Optional<IdeDataViewAuthorizer.IdeDataViewAccess> access =
        viewAuthorizer.authorize(environmentId, viewId, request.getHeader("Cookie"));
    if (access.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    if (!viewAuthorizer.belongsToUser(
        environmentId, viewId, request.getHeader("Cookie"), principal.userId())) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    executeLocal(environmentId, viewId, rawPathAndQuery, request, response, access.get());
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

    Object authenticatedUserId =
        request.getAttribute(IdeGatewayConstants.INTERNAL_USER_ID_ATTRIBUTE);
    if (!(authenticatedUserId instanceof UUID userId)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    Optional<IdeDataViewAuthorizer.IdeDataViewAccess> access =
        viewAuthorizer.authorize(environmentId, viewId, request.getHeader("Cookie"));
    if (access.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    if (!viewAuthorizer.belongsToUser(environmentId, viewId, request.getHeader("Cookie"), userId)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    executeLocal(environmentId, viewId, rawPathAndQuery, request, response, access.get());
  }

  private void executeLocal(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response,
      IdeDataViewAuthorizer.IdeDataViewAccess access)
      throws IOException {
    if (!workspaceQueryAllowed(access, request)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    Optional<EnvironmentTunnel> tunnel = environmentTunnels.local(environmentId);
    if (tunnel.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!tunnel.get().identity().equals(access.controlIdentity())) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_CONFLICT);
      return;
    }

    boolean isWs = StudioOriginPolicy.isWebSocketUpgrade(request);
    String daemonPath = IdeDataPaths.daemonPath(viewId, access.bindingEpoch(), rawPathAndQuery);
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
    TunnelExchange exchange =
        openLocalHttpExchange(
            tunnel,
            new TunnelHttpRequest(request.getMethod(), daemonPath, forwardHeaders),
            response,
            environmentId,
            viewId);
    if (exchange == null) {
      return;
    }
    try {
      pumpRequestBody(exchange, request);
      TunnelExchange.Response tunnelResponse =
          awaitResponseHeaders(exchange, response, environmentId, viewId);
      if (tunnelResponse == null) {
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
      copyResponseBody(exchange, response);
    } catch (Exception error) {
      log.info(
          "local tunnel http transfer failed; phase=LOCAL_TUNNEL_TRANSFER; environmentId={};"
              + " viewId={}; exception={}",
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

  /** 打开本地 HTTP 隧道；失败时已写 503 并返回 {@code null}。 */
  private TunnelExchange openLocalHttpExchange(
      EnvironmentTunnel tunnel,
      TunnelHttpRequest tunnelRequest,
      HttpServletResponse response,
      EnvironmentId environmentId,
      UUID viewId)
      throws IOException {
    try {
      return tunnel.openHttp(tunnelRequest);
    } catch (IllegalStateException error) {
      log.info(
          "local tunnel open failed; phase=LOCAL_TUNNEL_OPEN_HTTP; environmentId={}; viewId={};"
              + " exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return null;
    }
  }

  /** 在独立虚拟线程里把浏览器请求体灌入隧道并结束输出；失败时关闭整条 exchange。 */
  private void pumpRequestBody(TunnelExchange exchange, HttpServletRequest request) {
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
  }

  /** 等待 Daemon 响应头；失败时已写 502（仅当尚未提交）并返回 {@code null}。 */
  private TunnelExchange.Response awaitResponseHeaders(
      TunnelExchange exchange,
      HttpServletResponse response,
      EnvironmentId environmentId,
      UUID viewId)
      throws IOException {
    try {
      return exchange
          .responseHeaders()
          .toCompletableFuture()
          .get(IdeGatewayConstants.DEFAULT_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (Exception error) {
      log.info(
          "local tunnel response headers failed; phase=LOCAL_TUNNEL_RESPONSE_HEADERS;"
              + " environmentId={}; viewId={}; exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      if (!response.isCommitted()) {
        IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
      }
      return null;
    }
  }

  /** 逐块转发 Daemon 响应体到浏览器；响应输出流由本方法打开并关闭。 */
  private void copyResponseBody(TunnelExchange exchange, HttpServletResponse response)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
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
          "local tunnel open ws failed; phase=LOCAL_TUNNEL_OPEN_WS; environmentId={}; viewId={};"
              + " exception={}",
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
          "local tunnel ws headers failed; phase=LOCAL_TUNNEL_WS_HEADERS; environmentId={};"
              + " viewId={}; exception={}",
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
            "local tunnel ws handover failed; phase=LOCAL_TUNNEL_WS_HANDOVER; environmentId={};"
                + " viewId={}; exception={}",
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

  /** 受管绑定只认登记的 workspace 文件：{@code folder}/{@code ew} 选择与不匹配的 {@code workspace} 都不能覆盖绑定。 */
  private static boolean workspaceQueryAllowed(
      IdeDataViewAuthorizer.IdeDataViewAccess access, HttpServletRequest request) {
    if (request.getParameter("folder") != null || request.getParameter("ew") != null) {
      return false;
    }
    String[] workspace = request.getParameterValues("workspace");
    return workspace == null
        || (workspace.length == 1 && access.authorizedWorkspaces().contains(workspace[0]));
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
