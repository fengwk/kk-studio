package fun.fengwk.kkstudio.web.events;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUse;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseIntentQuery;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseIntentReply;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUsePaths;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseSnapshot;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.web.environment.cluster.ClusterInternalRequests;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 事件节点本地浏览器 IDE 使用登记的内部 REST 查询入口。
 *
 * <p>认证严格先于查询参数/请求体读取；端点只读本节点内存，不查全局数据库连接表。POST 精确查询连接/document 是否仍存活且登记了相同的 environment/view/claim
 * 意图；GET 返回同一连接/document 当前带 binding epoch 的续租证明。缺失或过期返回 404，不以空壳成功掩盖。
 */
@Slf4j
@RestController
@RequestMapping(IdeBrowserUsePaths.INTERNAL_PATH)
public class IdeBrowserUseInternalController {

  private static final Set<String> ACTIVE_PROOF_PARAMETERS =
      Set.of("eventNodeId", "connectionId", "documentId");

  private final ClusterInternalAuthentication authentication;
  private final IdeBrowserUseRegistry registry;
  private final ObjectMapper strictMapper =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  public IdeBrowserUseInternalController(
      ClusterInternalAuthentication authentication, IdeBrowserUseRegistry registry) {
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @PostMapping
  public IdeBrowserUseIntentReply findExactIntent(HttpServletRequest request) {
    authenticate(request);
    if (request.getQueryString() != null) {
      throw new AiValidationException("browserUse", "browser-use query is invalid");
    }
    IdeBrowserUseIntentQuery query = readBody(request, IdeBrowserUseIntentQuery.class);
    try {
      boolean matched =
          registry
              .findExactIntent(
                  query.identity(),
                  query.document(),
                  query.environment(),
                  query.view(),
                  query.claim())
              .isPresent();
      return new IdeBrowserUseIntentReply(matched);
    } catch (RuntimeException error) {
      throw new AiValidationException("browserUse", "browser-use query is invalid");
    }
  }

  @GetMapping
  public ResponseEntity<IdeBrowserUseSnapshot> activeBoundProofs(HttpServletRequest request) {
    authenticate(request);
    ConnectionIdentity identity;
    UUID documentId;
    try {
      Map<String, String[]> parameters = request.getParameterMap();
      if (!parameters.keySet().equals(ACTIVE_PROOF_PARAMETERS)) {
        throw new IllegalArgumentException("browser-use query parameters are invalid");
      }
      identity =
          ConnectionIdentity.parse(
              requireSingleParameter(parameters, "eventNodeId"),
              requireSingleParameter(parameters, "connectionId"));
      documentId =
          Uuids.parseCanonical(requireSingleParameter(parameters, "documentId"), "documentId");
    } catch (RuntimeException error) {
      throw new AiValidationException("browserUse", "browser-use query is invalid");
    }
    Optional<IdeBrowserUse> proofs = registry.activeBoundProofs(identity, documentId);
    return proofs
        .map(usage -> ResponseEntity.ok(IdeBrowserUseSnapshot.from(identity, usage)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  private void authenticate(HttpServletRequest request) {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
  }

  /** 只接受每个固定参数恰好一次；重复或缺失都按非法请求拒绝。 */
  private static String requireSingleParameter(Map<String, String[]> parameters, String name) {
    String[] values = parameters.get(name);
    if (values == null || values.length != 1) {
      throw new IllegalArgumentException(name + " must be present exactly once");
    }
    return values[0];
  }

  private <T> T readBody(HttpServletRequest request, Class<T> type) {
    byte[] payload = readBoundedBody(request);
    try {
      return strictMapper.readValue(payload, type);
    } catch (JacksonException error) {
      throw new AiValidationException("browserUse", "browser-use request body is invalid");
    }
  }

  private static byte[] readBoundedBody(HttpServletRequest request) {
    try (InputStream stream = request.getInputStream()) {
      byte[] data = stream.readNBytes(IdeBrowserUsePaths.MAX_BODY_BYTES + 1);
      if (data.length > IdeBrowserUsePaths.MAX_BODY_BYTES) {
        throw new BrowserUsePayloadTooLargeException();
      }
      return data;
    } catch (IOException error) {
      throw new AiValidationException("browserUse", "browser-use request body is unreadable");
    }
  }

  @ExceptionHandler(ClusterInternalAuthenticationException.class)
  public ResponseEntity<Map<String, String>> handleAuthentication(
      ClusterInternalAuthenticationException error) {
    log.debug("rejected internal browser-use request: {}", error.getMessage());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("message", "internal authentication failed"));
  }

  @ExceptionHandler(AiValidationException.class)
  public ResponseEntity<Map<String, String>> handleValidation(AiValidationException error) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("message", "browser-use request is invalid"));
  }

  @ExceptionHandler(BrowserUsePayloadTooLargeException.class)
  public ResponseEntity<Map<String, String>> handlePayloadTooLarge(
      BrowserUsePayloadTooLargeException error) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(Map.of("message", "browser-use request body is too large"));
  }

  private static final class BrowserUsePayloadTooLargeException extends RuntimeException {
    private BrowserUsePayloadTooLargeException() {
      super("browser-use request body is too large");
    }
  }
}
