package fun.fengwk.kkstudio.web.events;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;

import java.util.Map;

/** Binds the authenticated browser identity and session token to the event socket. */
@Component
final class StudioAccountHandshakeInterceptor implements HandshakeInterceptor {
  static final String TOKEN_ATTRIBUTE =
      StudioAccountHandshakeInterceptor.class.getName() + ".token";

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    if (!(request instanceof ServletServerHttpRequest servletRequest)) {
      return false;
    }
    HttpServletRequest httpRequest = servletRequest.getServletRequest();
    String token = StudioBrowserSession.token(httpRequest);
    StudioPrincipal principal =
        (StudioPrincipal) httpRequest.getAttribute(StudioBrowserSession.PRINCIPAL_ATTRIBUTE);
    if (token == null || principal == null) {
      return false;
    }
    attributes.put(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, principal);
    attributes.put(TOKEN_ATTRIBUTE, token);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception exception) {}
}
