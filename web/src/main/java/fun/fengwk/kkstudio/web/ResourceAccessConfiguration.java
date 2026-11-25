package fun.fengwk.kkstudio.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;

import java.util.Map;
import java.util.UUID;

/**
 * Resource gates run after route matching and before any controller reads or mutates the resource.
 */
@Configuration(proxyBeanMethods = false)
class ResourceAccessConfiguration implements WebMvcConfigurer {
  private final ResourceAccess access;

  ResourceAccessConfiguration(ResourceAccess access) {
    this.access = access;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                authorize(request);
                return true;
              }
            })
        .addPathPatterns(
            "/api/harness/**",
            "/api/canvas/canvases/{canvasId}",
            "/api/canvas/canvases/{canvasId}/**");
  }

  private void authorize(HttpServletRequest request) {
    if (!(request.getAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE)
        instanceof StudioPrincipal principal)) {
      throw DomainException.unauthorized("NOT_AUTHENTICATED", "Sign in required");
    }
    Object attributes = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    var path = attributes instanceof Map<?, ?> values ? values : Map.of();
    if (path.get("threadId") instanceof String thread) {
      access.requireThread(principal, id(thread));
    } else if (path.get("sessionId") instanceof String session) {
      access.require(principal, Kind.SESSION, id(session));
    } else if (path.get("canvasId") instanceof String canvas) {
      access.require(principal, Kind.CANVAS, id(canvas));
    } else if ("/api/harness/interactions"
            .equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
        && request.getParameter("rootThreadId") != null) {
      access.requireThread(principal, id(request.getParameter("rootThreadId")));
    } else if ("/api/harness/command-batches"
            .equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
        && principal.role() != StudioRole.ADMIN) {
      // This legacy creation endpoint has no user-owned target. It remains until Phase C.
      throw ResourceAccess.notFound();
    }
  }

  private static UUID id(String text) {
    try {
      return Uuids.parseCanonical(text, "resource ID");
    } catch (IllegalArgumentException ex) {
      throw DomainException.validationReason(
          "INVALID_ARGUMENT", "Expected a canonical resource ID");
    }
  }
}
