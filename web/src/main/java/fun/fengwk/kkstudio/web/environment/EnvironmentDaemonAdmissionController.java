package fun.fengwk.kkstudio.web.environment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.platform.daemon.DaemonArchitecture;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.platform.daemon.protocol.UpgradeRequired;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonAdmission;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonHandshake;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSessions;
import fun.fengwk.kkstudio.platform.environment.update.DaemonReleaseProvider;
import fun.fengwk.kkstudio.platform.environment.update.DaemonReleaseTarget;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Daemon 接入准入与 WebSocket 升级端点 ({@value #PATH})。
 *
 * <p>按规范校验请求头（安全隐藏令牌）、执行协议版本匹配（426 protobuf 载荷）、裁决准入并无缝升级至 原生 Jetty WebSocket。
 */
@Controller
public class EnvironmentDaemonAdmissionController {

  public static final String PATH = "/ws/daemon";
  private static final Logger log =
      LoggerFactory.getLogger(EnvironmentDaemonAdmissionController.class);
  private static final int MAX_BINARY_MESSAGE_SIZE = 1024 * 1024;
  private static final String BEARER_PREFIX = "Bearer ";

  private static final String HEADER_PROTOCOL = "KK-Daemon-Protocol";
  private static final String HEADER_INSTANCE = "KK-Daemon-Instance";
  private static final String HEADER_PLATFORM = "KK-Daemon-Platform";

  private final DaemonSessions sessions;
  private final ObjectProvider<DaemonReleaseProvider> releaseProvider;
  private final Scheduler scheduler;
  private final Executor executor;
  private final ByteBufferPool bufferPool;

  public EnvironmentDaemonAdmissionController(
      DaemonSessions sessions,
      ObjectProvider<DaemonReleaseProvider> releaseProvider,
      @Qualifier("daemonTransportScheduler") Scheduler scheduler,
      @Qualifier("daemonTransportExecutor") Executor executor,
      @Qualifier("daemonTransportByteBufferPool") ByteBufferPool bufferPool) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.releaseProvider = Objects.requireNonNull(releaseProvider, "releaseProvider");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool");
  }

  @GetMapping(PATH)
  public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!isWebSocketUpgradeRequest(request)) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Upgrade header required");
      return;
    }

    String authHeader = request.getHeader("Authorization");
    if (authHeader == null
        || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    String token = authHeader.substring(BEARER_PREFIX.length()).trim();
    if (token.isEmpty()) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }

    String instanceId = request.getHeader(HEADER_INSTANCE);
    if (instanceId == null || instanceId.isBlank()) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing daemon instance identifier");
      return;
    }
    instanceId = instanceId.trim();

    String protocolStr = request.getHeader(HEADER_PROTOCOL);
    if (protocolStr == null || protocolStr.isBlank()) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing daemon protocol version");
      return;
    }
    long protocolVersion;
    try {
      protocolVersion = DecimalLongs.parseNonNegative(protocolStr, HEADER_PROTOCOL);
    } catch (IllegalArgumentException e) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Malformed daemon protocol version");
      return;
    }

    if (protocolVersion != ControlProtocolCodec.PROTOCOL_VERSION) {
      sendUpgradeRequired(request, response);
      return;
    }

    DaemonAdmission admission;
    try {
      DaemonHandshake handshake = new DaemonHandshake(token, instanceId);
      admission = sessions.admit(handshake);
    } catch (IllegalArgumentException e) {
      log.warn("rejected malformed daemon handshake parameters");
      response.sendError(
          HttpServletResponse.SC_BAD_REQUEST, "Malformed daemon handshake parameters");
      return;
    } catch (Exception e) {
      log.error("daemon admission adjudication failed", e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return;
    }

    if (admission instanceof DaemonAdmission.Rejected) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }

    if (admission instanceof DaemonAdmission.RetryLater retryLater) {
      long seconds = Math.max(1L, retryLater.retryAfter().toSeconds());
      response.setHeader("Retry-After", String.valueOf(seconds));
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }

    if (admission instanceof DaemonAdmission.Admitted admitted) {
      performUpgrade(admitted, request, response);
    }
  }

  private void performUpgrade(
      DaemonAdmission.Admitted admitted, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    JettyWebSocketServerContainer container =
        JettyWebSocketServerContainer.getContainer(request.getServletContext());
    if (container == null) {
      container = JettyWebSocketServerContainer.ensureContainer(request.getServletContext());
    }
    container.setMaxBinaryMessageSize(MAX_BINARY_MESSAGE_SIZE);

    boolean upgraded;
    try {
      upgraded =
          container.upgrade(
              (upgradeRequest, upgradeResponse) ->
                  new EnvironmentDaemonWebSocketListener(
                      admitted, sessions, scheduler, executor, bufferPool),
              request,
              response);
    } catch (Exception e) {
      log.warn("websocket upgrade threw exception for environment {}", admitted.environmentId(), e);
      sessions.abandon(admitted);
      if (!response.isCommitted()) {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      }
      return;
    }

    if (!upgraded) {
      log.warn(
          "websocket upgrade rejected by container for environment {}", admitted.environmentId());
      sessions.abandon(admitted);
      if (!response.isCommitted()) {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "WebSocket upgrade failed");
      }
    }
  }

  private void sendUpgradeRequired(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    UpgradeRequired.Builder builder = UpgradeRequired.newBuilder();

    String platformHeader = request.getHeader(HEADER_PLATFORM);
    if (platformHeader != null && !platformHeader.isBlank()) {
      populateTargetVersion(builder, platformHeader.trim());
    }

    byte[] payload = builder.hasTargetVersion() ? builder.build().toByteArray() : new byte[0];
    response.setStatus(426);
    response.setContentType("application/x-protobuf");
    response.setContentLength(payload.length);
    response.getOutputStream().write(payload);
    response.flushBuffer();
  }

  private void populateTargetVersion(UpgradeRequired.Builder builder, String platformHeader) {
    DaemonReleaseProvider provider = releaseProvider.getIfAvailable();
    if (provider == null) {
      return;
    }
    int dash = platformHeader.indexOf('-');
    if (dash <= 0 || dash >= platformHeader.length() - 1) {
      return;
    }
    String osStr = platformHeader.substring(0, dash);
    String archStr = platformHeader.substring(dash + 1);
    DaemonOperatingSystem os;
    DaemonArchitecture arch;
    try {
      os = DaemonOperatingSystem.fromWireValue(osStr);
      arch = DaemonArchitecture.fromWireValue(archStr);
    } catch (IllegalArgumentException ignored) {
      return;
    }

    Optional<DaemonReleaseTarget> targetOpt = provider.resolveTarget(os, arch);
    targetOpt.ifPresent(target -> builder.setTargetVersion(target.targetVersion()));
  }

  private static boolean isWebSocketUpgradeRequest(HttpServletRequest request) {
    String connection = request.getHeader("Connection");
    String upgrade = request.getHeader("Upgrade");
    if (connection == null || upgrade == null) {
      return false;
    }
    boolean hasUpgradeInConnection = false;
    for (String token : connection.split(",")) {
      if ("upgrade".equalsIgnoreCase(token.trim())) {
        hasUpgradeInConnection = true;
        break;
      }
    }
    return hasUpgradeInConnection && "websocket".equalsIgnoreCase(upgrade.trim());
  }
}
