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

/** 在请求解析之前禁止缓存配置同步响应，覆盖成功、校验错误和事务失败。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfigSyncNoStoreFilter extends OncePerRequestFilter {

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    return !path.equals("/api/settings/sync") && !path.startsWith("/api/settings/sync/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    chain.doFilter(request, response);
  }
}
