package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

class IdeOriginPolicyTest {

  @Test
  void httpRequestAllowedScenarios() {
    HttpServletRequest options = mock(HttpServletRequest.class);
    when(options.getMethod()).thenReturn("OPTIONS");
    assertFalse(IdeOriginPolicy.httpRequestAllowed(options));

    HttpServletRequest crossSite = mock(HttpServletRequest.class);
    when(crossSite.getMethod()).thenReturn("GET");
    when(crossSite.getHeader("Sec-Fetch-Site")).thenReturn("cross-site");
    assertFalse(IdeOriginPolicy.httpRequestAllowed(crossSite));

    HttpServletRequest sameOriginNoOriginHeader = mock(HttpServletRequest.class);
    when(sameOriginNoOriginHeader.getMethod()).thenReturn("GET");
    when(sameOriginNoOriginHeader.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    assertTrue(IdeOriginPolicy.httpRequestAllowed(sameOriginNoOriginHeader));

    HttpServletRequest matchingOrigin = mock(HttpServletRequest.class);
    when(matchingOrigin.getMethod()).thenReturn("GET");
    when(matchingOrigin.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(matchingOrigin.getHeader("Origin")).thenReturn("http://localhost:8080");
    when(matchingOrigin.getScheme()).thenReturn("http");
    when(matchingOrigin.getServerName()).thenReturn("localhost");
    when(matchingOrigin.getServerPort()).thenReturn(8080);
    assertTrue(IdeOriginPolicy.httpRequestAllowed(matchingOrigin));

    HttpServletRequest mismatchOrigin = mock(HttpServletRequest.class);
    when(mismatchOrigin.getMethod()).thenReturn("GET");
    when(mismatchOrigin.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(mismatchOrigin.getHeader("Origin")).thenReturn("http://evil.com:8080");
    when(mismatchOrigin.getScheme()).thenReturn("http");
    when(mismatchOrigin.getServerName()).thenReturn("localhost");
    when(mismatchOrigin.getServerPort()).thenReturn(8080);
    assertFalse(IdeOriginPolicy.httpRequestAllowed(mismatchOrigin));
  }

  @Test
  void webSocketHandshakeAllowedScenarios() {
    HttpServletRequest missingOrigin = mock(HttpServletRequest.class);
    when(missingOrigin.getHeader("Origin")).thenReturn(null);
    assertFalse(IdeOriginPolicy.webSocketHandshakeAllowed(missingOrigin));

    HttpServletRequest crossSite = mock(HttpServletRequest.class);
    when(crossSite.getHeader("Sec-Fetch-Site")).thenReturn("cross-site");
    when(crossSite.getHeader("Origin")).thenReturn("http://localhost:8080");
    assertFalse(IdeOriginPolicy.webSocketHandshakeAllowed(crossSite));

    HttpServletRequest valid = mock(HttpServletRequest.class);
    when(valid.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(valid.getHeader("Origin")).thenReturn("http://localhost:8080");
    when(valid.getScheme()).thenReturn("http");
    when(valid.getServerName()).thenReturn("localhost");
    when(valid.getServerPort()).thenReturn(8080);
    assertTrue(IdeOriginPolicy.webSocketHandshakeAllowed(valid));
  }

  @Test
  void sameOriginExactValidations() {
    assertTrue(isOriginAllowed("https://studio.internal"));
    assertTrue(isOriginAllowed("https://studio.internal:443"));
    assertFalse(isOriginAllowed("http://studio.internal:443"));
    assertFalse(isOriginAllowed("https://studio.internal:8443"));
    assertFalse(isOriginAllowed("https://other.internal"));
    assertFalse(isOriginAllowed("https://user@studio.internal"));
    assertFalse(isOriginAllowed("https://studio.internal/path"));
    assertFalse(isOriginAllowed("https://studio.internal?q=1"));
    assertFalse(isOriginAllowed("not a valid uri"));
  }

  private static boolean isOriginAllowed(String origin) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(req.getHeader("Origin")).thenReturn(origin);
    when(req.getScheme()).thenReturn("https");
    when(req.getServerName()).thenReturn("studio.internal");
    when(req.getServerPort()).thenReturn(443);
    return IdeOriginPolicy.httpRequestAllowed(req);
  }

  @Test
  void isWebSocketUpgradeValidation() {
    HttpServletRequest ws = mock(HttpServletRequest.class);
    when(ws.getHeader("Upgrade")).thenReturn("  WebSocket  ");
    when(ws.getHeader("Connection")).thenReturn("keep-alive, Upgrade");
    assertTrue(IdeOriginPolicy.isWebSocketUpgrade(ws));

    // Substring in connection is not exact token -> false
    HttpServletRequest substringWs = mock(HttpServletRequest.class);
    when(substringWs.getHeader("Upgrade")).thenReturn("websocket");
    when(substringWs.getHeader("Connection")).thenReturn("not-upgrade, other");
    assertFalse(IdeOriginPolicy.isWebSocketUpgrade(substringWs));

    HttpServletRequest prefixWs = mock(HttpServletRequest.class);
    when(prefixWs.getHeader("Upgrade")).thenReturn("websocket");
    when(prefixWs.getHeader("Connection")).thenReturn("upgrade-action");
    assertFalse(IdeOriginPolicy.isWebSocketUpgrade(prefixWs));

    // Multiple Connection headers via getHeaders
    HttpServletRequest multiHeadersWs = mock(HttpServletRequest.class);
    when(multiHeadersWs.getHeader("Upgrade")).thenReturn("websocket");
    when(multiHeadersWs.getHeaders("Connection"))
        .thenReturn(Collections.enumeration(List.of("keep-alive", "close, upgrade")));
    assertTrue(IdeOriginPolicy.isWebSocketUpgrade(multiHeadersWs));

    HttpServletRequest multiHeadersNoWs = mock(HttpServletRequest.class);
    when(multiHeadersNoWs.getHeader("Upgrade")).thenReturn("websocket");
    when(multiHeadersNoWs.getHeaders("Connection"))
        .thenReturn(Collections.enumeration(List.of("keep-alive", "close")));
    assertFalse(IdeOriginPolicy.isWebSocketUpgrade(multiHeadersNoWs));

    HttpServletRequest notWs = mock(HttpServletRequest.class);
    when(notWs.getHeader("Upgrade")).thenReturn(null);
    when(notWs.getHeader("Connection")).thenReturn("keep-alive");
    assertFalse(IdeOriginPolicy.isWebSocketUpgrade(notWs));
  }
}
