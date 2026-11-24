package fun.fengwk.kkstudio.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer.PathSegment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ServletRequestPathUtils;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioOriginPolicy;
import fun.fengwk.kkstudio.platform.account.StudioRole;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class StudioAccountFilter extends OncePerRequestFilter {
  private static final Pattern DAEMON_SCRIPT =
      Pattern.compile("/api/platform/environments/[^/]+/(?:install|uninstall)-script");
  private static final Set<String> DIRECTORIES =
      Set.of(
          "/api/platform/agent-summaries",
          "/api/platform/model-summaries",
          "/api/platform/environment-summaries");
  private final StudioAccountService accounts;
  private final HandlerExceptionResolver problems;

  public StudioAccountFilter(
      StudioAccountService accounts,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver problems) {
    this.accounts = accounts;
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var parsed = ServletRequestPathUtils.parseAndCache(request);
    StringBuilder normalized = new StringBuilder();
    for (var element : parsed.pathWithinApplication().elements()) {
      if (element instanceof PathSegment segment) {
        normalized.append(segment.valueToMatch());
      } else {
        normalized.append(element.value());
      }
    }
    String path = normalized.toString();
    boolean api = under(path, "/api");
    if (api) {
      response.setHeader("Cache-Control", "no-store");
    }
    boolean socket = path.equals("/ws/events");
    boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    boolean login = "POST".equals(request.getMethod()) && path.equals("/api/auth/login");
    if ((!api && !socket && !under(path, "/ide"))
        || (read && DAEMON_SCRIPT.matcher(path).matches())) {
      chain.doFilter(request, response);
      return;
    }
    if ((socket || (api && !read)) && !StudioOriginPolicy.hasExactOrigin(request)) {
      reject(
          request, response, DomainException.forbidden("INVALID_ORIGIN", "Invalid Studio Origin"));
      return;
    }
    if (!login) {
      var principal = accounts.authenticate(StudioBrowserSession.token(request)).orElse(null);
      if (principal == null) {
        reject(
            request, response, DomainException.unauthorized("NOT_AUTHENTICATED", "Login required"));
        return;
      }
      boolean admin =
          under(path, "/api/users")
              || (under(path, "/api/platform") && !(read && DIRECTORIES.contains(path)));
      if (admin && principal.role() != StudioRole.ADMIN) {
        reject(
            request,
            response,
            DomainException.forbidden("ADMIN_REQUIRED", "Administrator required"));
        return;
      }
      request.setAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, principal);
    }
    chain.doFilter(request, response);
  }

  private static boolean under(String path, String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }

  private void reject(
      HttpServletRequest request, HttpServletResponse response, DomainException error) {
    problems.resolveException(request, response, null, error);
  }
}
