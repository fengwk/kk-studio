package fun.fengwk.kkstudio.ide.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalRequests;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

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
@RestController
@RequestMapping(IdeInternalPaths.INTERNAL_IDE_PREFIX + "/{environmentId}")
public class ClusterEnvironmentIdeInternalController {

  private static final int MAX_METADATA_BODY_BYTES = IdeInternalPaths.MAX_METADATA_BODY_BYTES;

  /** viewId 的路径约束：只匹配规范 UUID 形状，避免把同前缀下的其它内部子资源（如反向数据端点 {@code /reverse}）误判为 viewId 并返回 405。 */
  private static final String VIEW_ID =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  private final EnvironmentIdeServiceImpl localEnvironmentIdeService;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final ClusterInternalAuthentication authentication;

  /** 内部 metadata 严格解析器：未知字段与重复键一律拒绝，避免宽松绑定放大内部攻击面。 */
  private final ObjectMapper strictMapper =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

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
      @PathVariable UUID environmentId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    EnvironmentIdeOpenViewDTO body = readBody(request, EnvironmentIdeOpenViewDTO.class);
    return localEnvironmentIdeService.openView(environment, body);
  }

  @GetMapping("/views")
  public EnvironmentIdeViewsDTO listViews(
      @PathVariable UUID environmentId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    return localEnvironmentIdeService.listViews(environment);
  }

  @GetMapping("/directories")
  public EnvironmentIdeDirectoryListingDTO listDirectories(
      @PathVariable UUID environmentId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    return localEnvironmentIdeService.listDirectories(environment, request.getParameter("path"));
  }

  @PostMapping("/views/{viewId:" + VIEW_ID + "}/apply-mobile-layout")
  public void collapseMobile(
      @PathVariable UUID environmentId, @PathVariable String viewId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    EnvironmentIdeViewBindingDTO body = readBody(request, EnvironmentIdeViewBindingDTO.class);
    localEnvironmentIdeService.collapseMobile(environment, viewId, body);
  }

  @PutMapping("/views/{viewId:" + VIEW_ID + "}/directory")
  public EnvironmentIdeViewDTO changeDirectory(
      @PathVariable UUID environmentId, @PathVariable String viewId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    EnvironmentIdeChangeDirectoryDTO body =
        readBody(request, EnvironmentIdeChangeDirectoryDTO.class);
    return localEnvironmentIdeService.changeDirectory(environment, viewId, body);
  }

  @PostMapping("/views/{viewId:" + VIEW_ID + "}/focus-terminal")
  public void focusTerminal(
      @PathVariable UUID environmentId, @PathVariable String viewId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    EnvironmentIdeViewBindingDTO body = readBody(request, EnvironmentIdeViewBindingDTO.class);
    localEnvironmentIdeService.focusTerminal(environment, viewId, body);
  }

  @PostMapping("/views/{viewId:" + VIEW_ID + "}/close")
  public void closeView(
      @PathVariable UUID environmentId, @PathVariable String viewId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    EnvironmentIdeCloseViewDTO body = readBody(request, EnvironmentIdeCloseViewDTO.class);
    localEnvironmentIdeService.closeView(environment, viewId, body);
  }

  /** 认证 + owner 核对先于请求体读取；任一失败都不会有任何 daemon 副作用。 */
  private EnvironmentId authenticateAndAuthorize(HttpServletRequest request, UUID environmentId) {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
    EnvironmentId environment = EnvironmentId.of(environmentId);
    requireLocalOwner(environment);
    return environment;
  }

  private <T> T readBody(HttpServletRequest request, Class<T> type) {
    byte[] payload = readBoundedBody(request);
    try {
      return strictMapper.readValue(payload, type);
    } catch (JacksonException error) {
      throw DomainException.validation("environment", "internal IDE request body is invalid");
    }
  }

  /** 严格按硬上限读取请求体；超出上限立即 fail-closed。 */
  private static byte[] readBoundedBody(HttpServletRequest request) {
    try (InputStream stream = request.getInputStream()) {
      byte[] data = stream.readNBytes(MAX_METADATA_BODY_BYTES + 1);
      if (data.length > MAX_METADATA_BODY_BYTES) {
        throw new ResponseStatusException(
            HttpStatus.PAYLOAD_TOO_LARGE, "internal IDE request body is too large");
      }
      return data;
    } catch (IOException error) {
      throw DomainException.validation("environment", "internal IDE request body is unreadable");
    }
  }

  private void requireLocalOwner(EnvironmentId environmentId) {
    boolean local =
        clusterNodeRegistry.route(environmentId).filter(ClusterOwnerRoute::local).isPresent();
    if (!local) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "internal IDE request does not match this node's READY owner route");
    }
  }
}
