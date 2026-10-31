package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class IdeDataHeaderPolicyTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void forwardRequestHeadersFiltersCredentialsAndHopByHop() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeaderNames())
        .thenAnswer(
            inv ->
                Collections.enumeration(
                    List.of(
                        "Cookie",
                        "Authorization",
                        "Host",
                        "Content-Length",
                        "Connection",
                        "Upgrade",
                        "X-Forwarded-For",
                        "X-KK-Cluster-Signature",
                        "X-Custom-Safe",
                        "Sec-WebSocket-Key")));
    when(req.getHeaders("Cookie")).thenAnswer(inv -> Collections.enumeration(List.of("session=1")));
    when(req.getHeaders("Authorization"))
        .thenAnswer(inv -> Collections.enumeration(List.of("Bearer abc")));
    when(req.getHeaders("X-Custom-Safe"))
        .thenAnswer(inv -> Collections.enumeration(List.of("safe-value")));
    when(req.getHeaders("Sec-WebSocket-Key"))
        .thenAnswer(inv -> Collections.enumeration(List.of("key123")));
    when(req.getHeaders("Connection")).thenAnswer(inv -> Collections.enumeration(List.of("Close")));

    Map<String, List<String>> httpHeaders = IdeDataHeaderPolicy.forwardRequestHeaders(req, false);
    assertFalse(httpHeaders.containsKey("Cookie"));
    assertFalse(httpHeaders.containsKey("Authorization"));
    assertFalse(httpHeaders.containsKey("Host"));
    assertFalse(httpHeaders.containsKey("X-Forwarded-For"));
    assertFalse(httpHeaders.containsKey("X-KK-Cluster-Signature"));
    assertFalse(httpHeaders.containsKey("Sec-WebSocket-Key"));
    assertEquals(List.of("safe-value"), httpHeaders.get("X-Custom-Safe"));

    Map<String, List<String>> wsHeaders = IdeDataHeaderPolicy.forwardRequestHeaders(req, true);
    assertEquals(List.of("key123"), wsHeaders.get("Sec-WebSocket-Key"));
    assertEquals(List.of("safe-value"), wsHeaders.get("X-Custom-Safe"));
  }

  @Test
  void isForwardableResponseHeaderFiltersSetCookieAndHopByHop() {
    assertFalse(IdeDataHeaderPolicy.isForwardableResponseHeader("set-cookie", false));
    assertFalse(IdeDataHeaderPolicy.isForwardableResponseHeader("content-length", false));
    assertFalse(IdeDataHeaderPolicy.isForwardableResponseHeader("connection", false));
    assertFalse(IdeDataHeaderPolicy.isForwardableResponseHeader("upgrade", false));
    assertFalse(IdeDataHeaderPolicy.isForwardableResponseHeader("sec-websocket-protocol", false));
    assertTrue(IdeDataHeaderPolicy.isForwardableResponseHeader("sec-websocket-protocol", true));
    assertTrue(IdeDataHeaderPolicy.isForwardableResponseHeader("content-type", false));
  }

  @Test
  void applyResponseHeadersRebasesLocationAndWorkerScope() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContextPath()).thenReturn("");
    HttpServletResponse res = mock(HttpServletResponse.class);

    Map<String, List<String>> headers =
        Map.of(
            "Location", List.of("/dashboard"),
            "Service-Worker-Allowed", List.of("/"),
            "Content-Type", List.of("text/html"),
            "Set-Cookie", List.of("forbidden=true"));

    IdeDataHeaderPolicy.applyResponseHeaders(200, headers, req, res, ENV, VIEW, false);

    verify(res).setStatus(200);
    verify(res).addHeader("Location", "/environment-ide/" + ENV + "/" + VIEW + "/dashboard");
    verify(res).addHeader("Service-Worker-Allowed", "/environment-ide/" + ENV + "/" + VIEW + "/");
    verify(res).addHeader("Content-Type", "text/html");
  }

  @Test
  void sendErrorResetsWhenNotCommitted() throws IOException {
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(res.isCommitted()).thenReturn(false);
    IdeDataHeaderPolicy.sendError(res, 404);
    verify(res).reset();
    verify(res).sendError(404);
  }
}
