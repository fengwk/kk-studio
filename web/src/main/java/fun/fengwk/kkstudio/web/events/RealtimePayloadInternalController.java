package fun.fengwk.kkstudio.web.events;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.infra.realtime.RealtimePayloadBuffer;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.web.environment.cluster.ClusterInternalRequests;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Origin 节点内部 realtime payload 回拉入口。
 *
 * <p>只返回该节点短期 buffer 中仍有效、未过期/驱逐的 canonical event JSON。认证在读取任何 payload 之前完成，路径只接受 canonical UUID；
 * 缺失、过期或驱逐一律 404，绝不返回截断内容或替代数据。该端点不暴露 buffer 大小、Thread、URL 或任何凭据。
 */
@RestController
@RequestMapping(ClusterInternalPaths.INTERNAL_REALTIME_PREFIX)
public class RealtimePayloadInternalController {

  private static final String CANONICAL_UUID =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private final ClusterInternalAuthentication authentication;
  private final RealtimePayloadBuffer payloadBuffer;

  public RealtimePayloadInternalController(
      ClusterInternalAuthentication authentication, RealtimePayloadBuffer payloadBuffer) {
    this.authentication = authentication;
    this.payloadBuffer = payloadBuffer;
  }

  @GetMapping("/{payloadId:" + CANONICAL_UUID + "}")
  public ResponseEntity<String> payload(
      @PathVariable String payloadId, HttpServletRequest request) {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
    Optional<RealtimePayloadBuffer.Payload> payload = payloadBuffer.get(UUID.fromString(payloadId));
    if (payload.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(payload.get().canonicalJson());
  }

  @ExceptionHandler(ClusterInternalAuthenticationException.class)
  public ResponseEntity<Map<String, String>> handleAuthentication(
      ClusterInternalAuthenticationException error) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("message", "internal authentication failed"));
  }
}
