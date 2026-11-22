package fun.fengwk.kkstudio.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.util.List;

/**
 * SPA fallback 资源解析器。
 *
 * <p>当静态资源解析失败时，对于满足以下条件的请求返回 {@code index.html}：
 *
 * <ul>
 *   <li>HTTP 方法为 GET；
 *   <li>路径不以 {@code /api/}、{@code /actuator/}、{@code /ws/}、{@code /ide/} 或 {@code /internal/} 开头；
 *   <li>路径最后一段不包含点号（即无文件扩展名）。
 * </ul>
 *
 * 其余场景返回 {@code null}，由 Spring 返回 404。{@code /api/chat/chats/123} 这类已映射的 Controller 路径由 {@code
 * RequestMappingHandlerMapping} 优先匹配，本 resolver 不参与。
 *
 * @author fengwk
 */
public class SpaFallbackResourceResolver implements ResourceResolver {

  private static final String API_PREFIX = "/api/";
  private static final String ACTUATOR_PREFIX = "/actuator/";
  private static final String WS_PREFIX = "/ws/";
  private static final String IDE_PREFIX = "/ide/";
  private static final String INTERNAL_PREFIX = "/internal/";
  private static final String INDEX_HTML_PATH = "/index.html";
  private static final String INDEX_HTML_LOCATION = "static/index.html";
  private static final ClassPathResource INDEX_HTML = new ClassPathResource(INDEX_HTML_LOCATION);

  @Override
  public Resource resolveResource(
      HttpServletRequest request,
      String requestPath,
      List<? extends Resource> locations,
      ResourceResolverChain chain) {

    // ResourceHttpRequestHandler 在通过 PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE 或
    // getServletPath() 传入路径时，可能省略前导斜杠（例如 "api/foo" 而非 "/api/foo"）。
    // 为统一比对，先归一化为以 "/" 开头的形式。
    String normalizedPath = requestPath;
    if (!normalizedPath.isEmpty() && normalizedPath.charAt(0) != '/') {
      normalizedPath = "/" + normalizedPath;
    }

    Resource resolved = chain.resolveResource(request, normalizedPath, locations);
    if (resolved != null) {
      return resolved;
    }
    if (!shouldFallback(request, normalizedPath)) {
      return null;
    }
    return INDEX_HTML.exists() && INDEX_HTML.isReadable() ? INDEX_HTML : null;
  }

  @Override
  public String resolveUrlPath(
      String resourcePath, List<? extends Resource> locations, ResourceResolverChain chain) {
    return chain.resolveUrlPath(resourcePath, locations);
  }

  /**
   * SPA 外壳路径：显式的 {@code index.html}，或会回退到外壳的前端路由。
   *
   * <p>外壳内容随发布变化，缓存策略与其它静态文件不同；{@link SpaShellNoCacheFilter} 与 fallback 判定共用本方法， 避免两处各维护一份路由归类。
   */
  static boolean isShellPath(String path) {
    return INDEX_HTML_PATH.equals(path) || isSpaRoute(path);
  }

  /** 会触发 fallback 的路径形态（不考虑请求方法）：非保留前缀且末段无扩展名。 */
  private static boolean isSpaRoute(String path) {
    return !isReserved(path) && !hasExtension(path);
  }

  private static boolean shouldFallback(HttpServletRequest request, String path) {
    return "GET".equalsIgnoreCase(request.getMethod()) && isSpaRoute(path);
  }

  private static boolean isReserved(String path) {
    return path.startsWith(API_PREFIX)
        || path.equals("/api")
        || path.startsWith(ACTUATOR_PREFIX)
        || path.equals("/actuator")
        || path.startsWith(WS_PREFIX)
        || path.equals("/ws")
        || path.startsWith(IDE_PREFIX)
        || path.equals("/ide")
        || path.startsWith(INTERNAL_PREFIX)
        || path.equals("/internal");
  }

  private static boolean hasExtension(String path) {
    int lastSlash = path.lastIndexOf('/');
    String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    return lastSegment.lastIndexOf('.') >= 0;
  }
}
