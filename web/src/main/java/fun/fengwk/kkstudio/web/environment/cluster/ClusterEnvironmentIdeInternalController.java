package fun.fengwk.kkstudio.web.environment.cluster;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.platform.error.AiDomainException;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 跨节点 IDE 元数据命令的内部 REST 入口。
 *
 * <p>顺序是安全边界的核心：先在读取请求体之前完成 HMAC 认证与目标实例校验，再核对本节点确实持有该 Environment 的 READY owner 路由，最后才用 严格
 * ObjectMapper（未知字段/重复键拒绝）按硬上限读取请求体并直接调用本地 {@link EnvironmentIdeServiceImpl}（显式
 * qualifier），绝不递归回组合实现。 任何认证失败都在 401 处 fail-closed，未认证的大请求体不会进入解析，也不会有任何本地副作用；回执只有 ViewDTO，不含完整
 * cookie 或注册 token。
 *
 * <p>只有本 controller 自己的元数据路径受此约束；gateway 宿主的 {@code /http} 与 {@code /ws} 反向数据面不经过本 controller，也不受
 * 16KiB 限制。
 */
@Slf4j
@RestController
@RequestMapping(ClusterInternalPaths.INTERNAL_IDE_PREFIX + "/{environmentId}")
public class ClusterEnvironmentIdeInternalController {

  private static final int MAX_METADATA_BODY_BYTES = ClusterInternalPaths.MAX_METADATA_BODY_BYTES;

  /** viewId 的路径约束：只匹配规范 UUID 形状，避免把同前缀下的其它内部子资源（如反向数据端点 {@code /reverse}）误判为 viewId 并返回 405。 */
  private static final String VIEW_ID =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  private final EnvironmentIdeServiceImpl localEnvironmentIdeService;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final ClusterInternalAuthentication authentication;

  /** 内部 metadata 严格解析器：未知字段与重复键一律拒绝，避免宽松绑定放大内部攻击面。 */
  private final ObjectMapper strictMapper =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  public ClusterEnvironmentIdeInternalController(
      @Qualifier("localEnvironmentIdeService") EnvironmentIdeServiceImpl localEnvironmentIdeService,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalAuthentication authentication) {
    this.localEnvironmentIdeService = localEnvironmentIdeService;
    this.clusterNodeRegistry = clusterNodeRegistry;
    this.authentication = authentication;
  }

  @PostMapping("/views")
  public EnvironmentIdeViewDTO openView(
      @PathVariable String environmentId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    EnvironmentIdeOpenViewDTO body = readBody(request, EnvironmentIdeOpenViewDTO.class);
    return localEnvironmentIdeService.openView(environment, body);
  }

  @PutMapping("/{viewId:" + VIEW_ID + "}/directory")
  public EnvironmentIdeViewDTO changeDirectory(
      @PathVariable String environmentId, @PathVariable String viewId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    EnvironmentIdeChangeDirectoryDTO body =
        readBody(request, EnvironmentIdeChangeDirectoryDTO.class);
    return localEnvironmentIdeService.changeDirectory(environment, viewId, body);
  }

  @PostMapping("/{viewId:" + VIEW_ID + "}/terminal")
  public void focusTerminal(
      @PathVariable String environmentId, @PathVariable String viewId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    localEnvironmentIdeService.focusTerminal(environment, viewId);
  }

  @DeleteMapping("/{viewId:" + VIEW_ID + "}")
  public void closeView(
      @PathVariable String environmentId, @PathVariable String viewId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    localEnvironmentIdeService.closeView(environment, viewId);
  }

  /** 认证 + owner 核对先于请求体读取；任一失败都不会有任何 daemon 副作用。 */
  private EnvironmentId authenticateAndAuthorize(HttpServletRequest request, String environmentId) {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
    EnvironmentId environment = parseEnvironmentId(environmentId);
    requireLocalOwner(environment);
    return environment;
  }

  private <T> T readBody(HttpServletRequest request, Class<T> type) {
    byte[] payload = readBoundedBody(request);
    try {
      return strictMapper.readValue(payload, type);
    } catch (IOException error) {
      throw new AiValidationException("environment", "internal IDE request body is invalid");
    }
  }

  /** 严格按硬上限读取请求体；超出上限立即 fail-closed。 */
  private static byte[] readBoundedBody(HttpServletRequest request) {
    try (InputStream stream = request.getInputStream()) {
      byte[] data = stream.readNBytes(MAX_METADATA_BODY_BYTES + 1);
      if (data.length > MAX_METADATA_BODY_BYTES) {
        throw new ClusterInternalPayloadTooLargeException();
      }
      return data;
    } catch (IOException error) {
      throw new AiValidationException("environment", "internal IDE request body is unreadable");
    }
  }

  private void requireLocalOwner(EnvironmentId environmentId) {
    boolean local =
        clusterNodeRegistry.route(environmentId).filter(ClusterIdeOwnerRoute::local).isPresent();
    if (!local) {
      throw new AiEnvironmentIdeUnavailableException(
          "internal IDE request does not match this node's READY owner route");
    }
  }

  private static EnvironmentId parseEnvironmentId(String text) {
    try {
      return EnvironmentId.parse(text);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("environmentId", "environmentId must be a canonical UUID");
    }
  }

  @ExceptionHandler(ClusterInternalAuthenticationException.class)
  public ResponseEntity<Map<String, String>> handleAuthentication(
      ClusterInternalAuthenticationException error) {
    log.debug("rejected internal IDE request: {}", error.getMessage());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("message", "internal authentication failed"));
  }

  @ExceptionHandler(ClusterInternalPayloadTooLargeException.class)
  public ResponseEntity<Map<String, String>> handlePayloadTooLarge(
      ClusterInternalPayloadTooLargeException error) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(Map.of("message", "internal IDE request body is too large"));
  }

  @ExceptionHandler(AiDomainException.class)
  public ResponseEntity<Map<String, String>> handleDomain(AiDomainException error) {
    HttpStatus status =
        error instanceof AiValidationException
            ? HttpStatus.BAD_REQUEST
            : error instanceof AiEnvironmentIdeUnavailableException
                ? HttpStatus.CONFLICT
                : HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status).body(Map.of("message", message(error)));
  }

  private static String message(AiDomainException error) {
    String text = error.getMessage();
    return text == null || text.isBlank() ? "internal IDE request failed" : text;
  }

  /** 内部 metadata 请求体超出硬上限；映射为 413。 */
  private static final class ClusterInternalPayloadTooLargeException extends RuntimeException {

    private ClusterInternalPayloadTooLargeException() {
      super("internal IDE request body is too large");
    }
  }
}
