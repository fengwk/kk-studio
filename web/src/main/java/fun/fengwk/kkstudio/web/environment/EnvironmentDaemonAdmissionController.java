package fun.fengwk.kkstudio.web.environment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonArchitecture;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.harness.environment.protocol.UpgradeRequired;
import fun.fengwk.kkstudio.harness.environment.server.DaemonAdmission;
import fun.fengwk.kkstudio.harness.environment.server.DaemonHandshake;
import fun.fengwk.kkstudio.harness.environment.server.DaemonSessions;
import fun.fengwk.kkstudio.platform.environment.update.DaemonReleaseProvider;
import fun.fengwk.kkstudio.platform.environment.update.DaemonReleaseTarget;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Daemon 接入准入与 WebSocket 升级端点 ({@value #PATH})。
 *
 * <p>按规范校验请求头（安全隐藏令牌）、执行协议版本匹配（426 protobuf 载荷）、裁决准入并无缝升级至 原生 Jetty WebSocket。
 */
@Controller
public class EnvironmentDaemonAdmissionController {

  public static final String PATH = "/api/harness/environment-daemon";
  private static final Logger log =
      LoggerFactory.getLogger(EnvironmentDaemonAdmissionController.class);
  private static final int MAX_BINARY_MESSAGE_SIZE = 1024 * 1024;
  private static final String BEARER_PREFIX = "Bearer ";

  private static final String HEADER_PROTOCOL_1 = "KK-Daemon-Protocol";
  private static final String HEADER_PROTOCOL_2 = "X-Daemon-Protocol-Version";
  private static final String HEADER_INSTANCE_1 = "KK-Daemon-Instance";
  private static final String HEADER_INSTANCE_2 = "X-Daemon-Instance-Id";
  private static final String HEADER_PLATFORM_1 = "KK-Daemon-Platform";
  private static final String HEADER_PLATFORM_2 = "X-Daemon-Platform";

  private final DaemonSessions sessions;
  private final ObjectProvider<DaemonReleaseProvider> releaseProvider;
  private final Scheduler scheduler;

  public EnvironmentDaemonAdmissionController(
      DaemonSessions sessions,
      ObjectProvider<DaemonReleaseProvider> releaseProvider,
      @Qualifier("daemonTransportScheduler") Scheduler scheduler) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.releaseProvider = Objects.requireNonNull(releaseProvider, "releaseProvider");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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

    String instanceId = getFirstHeader(request, HEADER_INSTANCE_1, HEADER_INSTANCE_2);
    if (instanceId == null || instanceId.isBlank()) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing daemon instance identifier");
      return;
    }
    instanceId = instanceId.trim();

    String protocolStr = getFirstHeader(request, HEADER_PROTOCOL_1, HEADER_PROTOCOL_2);
    if (protocolStr == null || protocolStr.isBlank()) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing daemon protocol version");
      return;
    }
    int protocolVersion;
    try {
      protocolVersion = Integer.parseInt(protocolStr.trim());
    } catch (NumberFormatException e) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Malformed daemon protocol version");
      return;
    }

    if (protocolVersion != ControlProtocolCodec.PROTOCOL_VERSION) {
      sendUpgradeRequired(request, response);
      return;
    }

    DaemonHandshake handshake = new DaemonHandshake(token, instanceId);
    DaemonAdmission admission;
    try {
      admission = sessions.admit(handshake);
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
      DaemonAdmission.Admitted admitted,
      HttpServletRequest request,
      HttpServletResponse response)
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
                  new EnvironmentDaemonWebSocketListener(admitted, sessions, scheduler),
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
      log.warn("websocket upgrade rejected by container for environment {}", admitted.environmentId());
      sessions.abandon(admitted);
      if (!response.isCommitted()) {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "WebSocket upgrade failed");
      }
    }
  }

  private void sendUpgradeRequired(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    UpgradeRequired.Builder builder =
        UpgradeRequired.newBuilder()
            .setTargetProtocolVersion(ControlProtocolCodec.PROTOCOL_VERSION);

    String platformHeader = getFirstHeader(request, HEADER_PLATFORM_1, HEADER_PLATFORM_2);
    if (platformHeader != null && !platformHeader.isBlank()) {
      populateArtifactMetadata(builder, platformHeader.trim());
    }

    byte[] payload = builder.build().toByteArray();
    response.setStatus(426);
    response.setContentType("application/x-protobuf");
    response.setContentLength(payload.length);
    response.getOutputStream().write(payload);
    response.flushBuffer();
  }

  private void populateArtifactMetadata(
      UpgradeRequired.Builder builder, String platformHeader) {
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
    if (targetOpt.isPresent()) {
      DaemonReleaseTarget target = targetOpt.get();
      builder.setArtifactUrl(target.artifactUrl());
      builder.setArtifactSha256(target.artifactSha256());
    }
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

  private static String getFirstHeader(HttpServletRequest request, String... names) {
    for (String name : names) {
      String value = request.getHeader(name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
