package fun.fengwk.kkstudio.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * 要求 SPA 外壳（{@code index.html} 与其 fallback 响应）每次回源验证。
 *
 * <p>外壳内容随发布变化，文件名却不带内容 hash；缺少缓存策略时浏览器会按启发式规则缓存它，升级后可能继续引用已删除的 hash 资源。带 hash 的构建产物由 {@link
 * SpaFallbackConfig} 的资源处理器长缓存，其它静态文件保持默认协商。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SpaShellNoCacheFilter extends OncePerRequestFilter {

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    if ("HEAD".equalsIgnoreCase(request.getMethod())) {
      return !"/index.html".equals(path) && !"/".equals(path);
    }
    return !"GET".equalsIgnoreCase(request.getMethod())
        || !SpaFallbackResourceResolver.isShellPath(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    chain.doFilter(request, response);
  }
}
