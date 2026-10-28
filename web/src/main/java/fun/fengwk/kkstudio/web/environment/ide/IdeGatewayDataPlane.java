package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectTimeoutException;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.data.IdeDataGenerationReplacedException;
import fun.fengwk.kkstudio.ide.data.IdeDataUnavailableException;
import fun.fengwk.kkstudio.ide.mux.MuxBackpressureException;
import fun.fengwk.kkstudio.ide.mux.MuxClose;
import fun.fengwk.kkstudio.ide.mux.MuxConnectionClosedException;
import fun.fengwk.kkstudio.ide.mux.MuxDataKind;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxHeaders;
import fun.fengwk.kkstudio.ide.mux.MuxReceivedData;
import fun.fengwk.kkstudio.ide.mux.MuxReset;
import fun.fengwk.kkstudio.ide.mux.MuxStream;
import fun.fengwk.kkstudio.ide.mux.MuxStreamClosedException;
import fun.fengwk.kkstudio.ide.mux.MuxStreamListener;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IDE 同源网关的 HTTP 数据面：在 owner 上把浏览器 HTTP 逻辑流映射到当前复用数据连接的一条 mux 流。
 *
 * <p>浏览器入口是 {@code /environment-ide/{environmentId}/{viewId}/...}。非 owner 节点先按 owner 路由把请求单跳转发到
 * {@code /internal/environment-ide/{environmentId}/data-http?viewId=...&path=...}，owner
 * 再用同一套视图授权与数据面执行； 不依赖粘性会话，也不在非 owner 保存浏览器会话状态。
 *
 * <p>每个 exchange 的请求与响应共享一条逻辑流：请求体与 END 由独立虚拟线程有序发送，响应 DATA 只有真实写入 servlet 输出后才归还 credit。收到响应 END
 * 后先 flush 浏览器输出，成功才发送 COMPLETE；任何失败都用 RESET 结束该流，物理写失败不重试、不重放。
 */
@Slf4j
@Component
public final class IdeGatewayDataPlane {

  private static final int MAX_REQUEST_CHUNK_BYTES = 64 * 1024;

  private static final long WAIT_SLICE_MILLIS = 100L;

  private static final long BACKPRESSURE_RETRY_MILLIS = 5L;

  private final IdeCommandTransport transport;
  private final IdeGatewayProperties properties;
  private final IdeBrowserSessionHandler browserSessionHandler;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final IdeRemoteDataPlaneForwarder remoteForwarder;
  private final IdeDataConnectionRegistry dataConnections;
  private final IdeDataViewAuthorizer viewAuthorizer;

  public IdeGatewayDataPlane(
      IdeCommandTransport transport,
      IdeGatewayProperties properties,
      ClusterNodeRegistry clusterNodeRegistry,
      IdeRemoteDataPlaneForwarder remoteForwarder,
      IdeDataConnectionRegistry dataConnections,
      IdeDataViewAuthorizer viewAuthorizer,
      IdeBrowserSessionHandler browserSessionHandler) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.browserSessionHandler =
        Objects.requireNonNull(browserSessionHandler, "browserSessionHandler");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.remoteForwarder = Objects.requireNonNull(remoteForwarder, "remoteForwarder");
    this.dataConnections = Objects.requireNonNull(dataConnections, "dataConnections");
    this.viewAuthorizer = Objects.requireNonNull(viewAuthorizer, "viewAuthorizer");
  }

  /**
   * 处理一个浏览器视图作用域的 HTTP exchange。
   *
   * @param rawPathAndQuery view 基址之后的原始 path（含 query），例如 {@code /src/main.ts?x=1}
   */
  public void handleHttp(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    if (!IdeOriginPolicy.httpRequestAllowed(request)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    if (route.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!route.get().local()) {
      remoteForwarder.forwardDataHttp(
          route.get(), environmentId, viewId, rawPathAndQuery, request, response);
      return;
    }
    executeHttp(environmentId, viewId, rawPathAndQuery, request, response);
  }

  /** owner 内部入口：HMAC 与本节点 owner 归属已由 controller 校验；不重复做浏览器 Origin 与跨节点转发。 */
  public void handleInternalHttp(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    executeHttp(environmentId, viewId, rawPathAndQuery, request, response);
  }

  private void executeHttp(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    IdeDataViewAuthorizer.IdeDataViewAccess access =
        authorize(environmentId, viewId, request.getHeader("Cookie"));
    if (access == null) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    if (!workspaceQueryAllowed(access, request)) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    List<MuxHeader> headers = IdeDataHeaderPolicy.forwardRequestHeaders(request, false);
    String requestPath;
    try {
      requestPath = IdeDataInternalPaths.viewPath(rawPathAndQuery);
    } catch (IllegalArgumentException error) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_REQUEST);
      return;
    }
    HttpExchange exchange = new HttpExchange(environmentId, viewId, request, response);
    MuxStream stream;
    try {
      stream =
          dataConnections.openHttp(
              environmentId,
              viewId,
              access.bindingEpoch(),
              request.getMethod(),
              requestPath,
              headers,
              access.controlIdentity(),
              exchange);
    } catch (IdeDataConnectTimeoutException error) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT);
      return;
    } catch (IdeDataGenerationReplacedException error) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_CONFLICT);
      return;
    } catch (IdeDataUnavailableException | MuxBackpressureException error) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    } catch (IllegalArgumentException error) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_REQUEST);
      return;
    }

    exchange.attach(stream);
    stream.closed().whenComplete((ignored, error) -> exchange.onStreamClosed(error));
    try {
      exchange.run();
    } catch (IdeHttpFailure failure) {
      if (!response.isCommitted()) {
        IdeDataHeaderPolicy.sendError(response, failure.status());
      } else {
        throw new IOException(
            "IDE HTTP exchange failed after the browser response was committed", failure);
      }
    }
  }

  private IdeDataViewAuthorizer.IdeDataViewAccess authorize(
      EnvironmentId environmentId, UUID viewId, String cookieHeader) {
    Optional<IdeDataViewAuthorizer.IdeDataViewAccess> access =
        viewAuthorizer.authorize(environmentId, viewId, cookieHeader);
    if (access.isEmpty() || !access.get().viewId().equals(viewId)) {
      return null;
    }
    return access.get();
  }

  /** IDE 根路径上的 {@code workspace}/{@code folder} 只能指向本视图已授权的受管 workspace。 */
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

  /** 视图作用域的 bootstrap 入口；非 owner 节点单跳到 owner 后由浏览器会话切片消费票据。 */
  public void handleBootstrap(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    if (forwardManagementRemote(environmentId, viewId, rawPathAndQuery, request, response)) {
      return;
    }
    browserSessionHandler.handleBootstrap(environmentId, viewId, request, response);
  }

  /** 视图作用域的会话探测入口；非 owner 节点单跳到 owner 后执行同一会话校验。 */
  public void handleSession(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    if (forwardManagementRemote(environmentId, viewId, rawPathAndQuery, request, response)) {
      return;
    }
    browserSessionHandler.handleSession(environmentId, viewId, request, response);
  }

  /** owner 内部入口：view、owner 与 HMAC 已由 controller 校验，直接执行浏览器会话逻辑。 */
  public void handleLocalBootstrap(
      EnvironmentId environmentId,
      UUID viewId,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    browserSessionHandler.handleBootstrap(environmentId, viewId, request, response);
  }

  public void handleLocalSession(
      EnvironmentId environmentId,
      UUID viewId,
      HttpServletRequest request,
      HttpServletResponse response) {
    browserSessionHandler.handleSession(environmentId, viewId, request, response);
  }

  private boolean forwardManagementRemote(
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Optional<ClusterOwnerRoute> route = clusterNodeRegistry.route(environmentId);
    if (route.isEmpty()) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return true;
    }
    if (route.get().local()) {
      return false;
    }
    remoteForwarder.forwardManagementHttp(
        route.get(), environmentId, viewId, rawPathAndQuery, request, response);
    return true;
  }

  /** 浏览器 view 基址；{@code viewId == null} 时只返回 Environment 根路径。 */
  public static String viewBase(
      HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    String contextPath = request.getContextPath();
    String base =
        contextPath == null || contextPath.isEmpty()
            ? "/environment-ide/" + environmentId + "/"
            : (contextPath.endsWith("/") ? contextPath : contextPath + "/")
                + "environment-ide/"
                + environmentId
                + "/";
    return viewId == null ? base : base + viewId + "/";
  }

  public static String rebaseLocation(
      String location, HttpServletRequest request, EnvironmentId environmentId) {
    return rebaseLocation(location, request, environmentId, null);
  }

  /** 把上游绝对 path 重定位到当前浏览器 view 基址；绝对 URL 保持不变。 */
  public static String rebaseLocation(
      String location, HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    if (location == null || location.isBlank()) {
      return location;
    }
    if (location.startsWith("/") && !location.startsWith("//")) {
      String relative = internalViewRelative(location, viewId);
      String base = viewBase(request, environmentId, viewId);
      return base + relative.substring(1);
    }
    return location;
  }

  /** Worker scope 只能落在当前浏览器 view；无效或越界声明收窄到 view 根。 */
  static String rebaseWorkerScope(
      String scope, HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    String base = viewBase(request, environmentId, viewId);
    if (viewId == null || scope.indexOf('?') >= 0 || scope.indexOf('#') >= 0) {
      return base;
    }
    String relative = internalViewRelative(scope, viewId);
    if (!relative.equals(scope) && IdeDataInternalPaths.isSafeViewPath(relative)) {
      return base + relative.substring(1);
    }
    try {
      URI uri = URI.create(scope);
      if (uri.isAbsolute()
          || uri.getRawAuthority() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        return base;
      }
      String path = uri.getRawPath();
      if (path != null && path.startsWith("./")) {
        path = path.substring(2);
      }
      if (path == null || !IdeDataInternalPaths.isSafeViewPath("/" + path)) {
        return base;
      }
      String resolved = URI.create(request.getRequestURI()).resolve(uri).getRawPath();
      return resolved.startsWith(base) ? resolved : base;
    } catch (IllegalArgumentException error) {
      return base;
    }
  }

  /** 真实 code-server 的绝对 Location 带内部挂载；只剥离当前 view 的 `/__kk/ide/view/...` 基址。 */
  private static String internalViewRelative(String location, UUID viewId) {
    if (viewId == null) {
      return location;
    }
    String prefix = "/__kk/ide/view/" + viewId + "/";
    if (!location.startsWith(prefix)) {
      return location;
    }
    int slash = location.indexOf('/', prefix.length());
    int question = location.indexOf('?', prefix.length());
    int end;
    if (slash < 0 && question < 0) {
      end = location.length();
    } else if (slash < 0) {
      end = question;
    } else if (question < 0) {
      end = slash;
    } else {
      end = Math.min(slash, question);
    }
    if (!positiveDecimal(location.substring(prefix.length(), end))) {
      return location;
    }
    String tail = location.substring(end);
    if (tail.isEmpty()) {
      return "/";
    }
    return tail.charAt(0) == '?' ? "/" + tail : tail;
  }

  private static boolean positiveDecimal(String value) {
    if (value.isEmpty() || value.charAt(0) == '0') {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  static final class IdeHttpFailure extends Exception {

    private final int status;

    IdeHttpFailure(int status, String message) {
      super(message);
      this.status = status;
    }

    IdeHttpFailure(int status, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
    }

    int status() {
      return status;
    }
  }

  /** 单条 HTTP 逻辑流的请求发送与响应交付状态机。 */
  private final class HttpExchange implements MuxStreamListener {

    private final EnvironmentId environmentId;
    private final UUID viewId;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final Object dispatchLock = new Object();
    private final List<Runnable> pendingCallbacks = new ArrayList<>();
    private final long idleTimeoutNanos;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CompletableFuture<Void> terminal = new CompletableFuture<>();
    private volatile long lastProgressNanos = System.nanoTime();
    private volatile boolean headersReceived;
    private volatile boolean responseEnded;
    private volatile boolean completeWriteSucceeded;
    private volatile boolean completionInProgress;
    private volatile Thread sender;
    private MuxStream stream;
    private boolean attached;
    private boolean draining;

    private HttpExchange(
        EnvironmentId environmentId,
        UUID viewId,
        HttpServletRequest request,
        HttpServletResponse response) {
      this.environmentId = environmentId;
      this.viewId = viewId;
      this.request = request;
      this.response = response;
      this.idleTimeoutNanos = properties.requireRequestTimeout().toNanos();
    }

    /** OPEN 返回后绑定流，并按 wire 顺序重放等待期间到达的回调。 */
    private void attach(MuxStream stream) {
      this.stream = Objects.requireNonNull(stream, "stream");
      synchronized (dispatchLock) {
        attached = true;
      }
      drainCallbacks();
    }

    private void dispatch(Runnable callback) {
      synchronized (dispatchLock) {
        pendingCallbacks.add(callback);
      }
      drainCallbacks();
    }

    private void drainCallbacks() {
      synchronized (dispatchLock) {
        if (!attached || draining) {
          return;
        }
        draining = true;
      }
      while (true) {
        Runnable callback;
        synchronized (dispatchLock) {
          if (pendingCallbacks.isEmpty()) {
            draining = false;
            return;
          }
          callback = pendingCallbacks.removeFirst();
        }
        try {
          callback.run();
        } catch (RuntimeException error) {
          fail(error, 502);
        }
      }
    }

    private void run() throws IdeHttpFailure {
      sender =
          Thread.ofVirtual().name("ide-http-request-" + stream.hashCode()).start(this::sendRequest);
      try {
        while (!terminal.isDone()) {
          long remaining = lastProgressNanos + idleTimeoutNanos - System.nanoTime();
          if (remaining <= 0L) {
            fail(new SocketTimeoutException("IDE HTTP exchange idle timeout"), 504);
            break;
          }
          try {
            terminal.get(
                Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(WAIT_SLICE_MILLIS)),
                TimeUnit.NANOSECONDS);
          } catch (TimeoutException ignored) {
            // 继续等待；持续有响应进展会刷新 lastProgressNanos。
          } catch (ExecutionException error) {
            // 失败由下方统一映射。
            break;
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail(error, 503);
            break;
          }
        }
      } finally {
        Thread current = sender;
        if (current != null) {
          current.interrupt();
        }
      }
      Throwable error = failure.get();
      if (error != null) {
        int status = error instanceof IdeHttpFailure failureError ? failureError.status() : 502;
        throw new IdeHttpFailure(status, "IDE HTTP exchange failed", error);
      }
    }

    private void sendRequest() {
      try (InputStream input = request.getInputStream()) {
        byte[] buffer = new byte[MAX_REQUEST_CHUNK_BYTES];
        int read;
        while (!terminal.isDone() && (read = input.read(buffer)) > 0) {
          byte[] chunk = new byte[read];
          System.arraycopy(buffer, 0, chunk, 0, read);
          if (!sendData(chunk)) {
            return;
          }
        }
        if (!terminal.isDone()) {
          sendEnd();
        }
      } catch (MuxStreamClosedException | MuxConnectionClosedException ignored) {
        // 响应先结束或连接已 fail-stop；无需重放请求。
      } catch (IOException error) {
        fail(error, 400);
      } catch (RuntimeException error) {
        fail(error, 502);
      }
    }

    private boolean sendData(byte[] chunk) {
      while (!terminal.isDone() && stream.isOpen()) {
        try {
          return awaitWrite(stream.sendHttpData(chunk), 502);
        } catch (MuxBackpressureException error) {
          sleepBeforeRetry();
        } catch (MuxStreamClosedException | MuxConnectionClosedException error) {
          return false;
        } catch (RuntimeException error) {
          fail(error, 502);
          return false;
        }
      }
      return false;
    }

    private void sendEnd() {
      for (int attempt = 0; attempt < 2; attempt++) {
        try {
          awaitWrite(stream.sendEnd(), 502);
          return;
        } catch (MuxBackpressureException ignored) {
          sleepBeforeRetry();
        } catch (MuxStreamClosedException | MuxConnectionClosedException ignored) {
          return;
        } catch (RuntimeException error) {
          fail(error, 502);
          return;
        }
      }
      fail(new MuxStreamClosedException("request END could not be sent"), 502);
    }

    private boolean awaitWrite(CompletableFuture<Void> write, int failureStatus) {
      while (true) {
        if (terminal.isDone()) {
          write.cancel(true);
          return false;
        }
        try {
          write.get(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS);
          return !terminal.isDone();
        } catch (TimeoutException ignored) {
          // 等待物理写或上游 credit；期间仍响应终端状态。
        } catch (ExecutionException error) {
          fail(error.getCause(), failureStatus);
          return false;
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          fail(error, 503);
          return false;
        } catch (RuntimeException error) {
          fail(error, failureStatus);
          return false;
        }
      }
    }

    private void sleepBeforeRetry() {
      try {
        Thread.sleep(BACKPRESSURE_RETRY_MILLIS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        fail(error, 503);
      }
    }

    @Override
    public void onReady(MuxStream stream) {
      dispatch(() -> progressed());
    }

    @Override
    public void onHeaders(MuxStream stream, MuxHeaders headers) {
      dispatch(
          () -> {
            if (terminal.isDone() || headersReceived) {
              return;
            }
            IdeDataHeaderPolicy.applyResponseHeaders(
                headers, request, response, environmentId, viewId);
            headersReceived = true;
            progressed();
          });
    }

    @Override
    public void onData(MuxStream stream, MuxReceivedData data) {
      dispatch(
          () -> {
            if (terminal.isDone()) {
              data.consumed();
              return;
            }
            if (data.kind() != MuxDataKind.HTTP) {
              fail(new IOException("WS DATA received on an HTTP stream"), 502);
              data.consumed();
              return;
            }
            try {
              response.getOutputStream().write(data.payload());
              progressed();
            } catch (IOException | RuntimeException error) {
              fail(error, 502);
            } finally {
              data.consumed();
            }
          });
    }

    @Override
    public void onEnd(MuxStream stream) {
      dispatch(
          () -> {
            if (terminal.isDone()) {
              return;
            }
            responseEnded = true;
            progressed();
            try {
              response.getOutputStream().flush();
            } catch (IOException error) {
              fail(error, 502);
              return;
            }
            completionInProgress = true;
            try {
              stream
                  .complete()
                  .whenComplete(
                      (ignored, error) -> {
                        if (error == null) {
                          completeWriteSucceeded = true;
                          success();
                        } else {
                          fail(error, 502);
                        }
                      });
            } catch (RuntimeException error) {
              // Early responses before request END cannot COMPLETE; fail only this stream.
              fail(error, 502);
            }
          });
    }

    @Override
    public void onReset(MuxStream stream, MuxReset reset) {
      if (!terminal.isDone()) {
        fail(new IOException("IDE data stream was reset"), 502);
      }
    }

    @Override
    public void onClose(MuxStream stream, MuxClose close) {
      fail(new IOException("CLOSE received on an HTTP stream"), 502);
    }

    void onStreamClosed(Throwable error) {
      if (terminal.isDone()) {
        return;
      }
      if (error != null) {
        fail(error, 502);
      } else if (completeWriteSucceeded) {
        success();
      } else if (completionInProgress) {
        // complete() may close synchronously before its returned future can be observed.
        // Only that actual write future may decide success or failure.
        return;
      } else {
        fail(new IOException("IDE HTTP stream closed before COMPLETE was written"), 502);
      }
    }

    private void progressed() {
      lastProgressNanos = System.nanoTime();
    }

    private void success() {
      terminal.complete(null);
    }

    private void fail(Throwable error, int status) {
      if (error == null) {
        error = new IOException("IDE HTTP exchange failed");
      }
      if (!failure.compareAndSet(
          null, new IdeHttpFailure(status, "IDE HTTP exchange failed", error))) {
        return;
      }
      terminal.completeExceptionally(error);
      MuxStream activeStream = stream;
      if (activeStream != null && activeStream.isOpen()) {
        try {
          activeStream.reset("HTTP_FAILED", "IDE HTTP exchange failed");
        } catch (RuntimeException ignored) {
          // 连接/流已收敛或管理队列耗尽；fail-stop 已由相应路径负责。
        }
      }
      Thread current = sender;
      if (current != null) {
        current.interrupt();
      }
    }
  }
}
