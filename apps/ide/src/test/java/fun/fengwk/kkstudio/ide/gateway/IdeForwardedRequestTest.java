package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class IdeForwardedRequestTest {

  @Test
  void parsesPathAndParametersFromRawPathAndQuery() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader(IdeForwardedRequest.SCHEME_HEADER)).thenReturn("https");

    IdeForwardedRequest forwarded =
        IdeForwardedRequest.of(req, "/src/app.ts?workspace=%2Froot&flag=1&flag=2");

    assertEquals("/src/app.ts", forwarded.getRequestURI());
    assertEquals("workspace=%2Froot&flag=1&flag=2", forwarded.getQueryString());
    assertEquals("/root", forwarded.getParameter("workspace"));
    assertEquals("1", forwarded.getParameter("flag"));
    assertArrayEquals(new String[] {"1", "2"}, forwarded.getParameterValues("flag"));
    assertNull(forwarded.getParameter("unknown"));
    assertTrue(forwarded.getParameterMap().containsKey("workspace"));
    assertTrue(forwarded.isSecure());
  }

  @Test
  void handlesNoQueryStringAndInsecureScheme() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader(IdeForwardedRequest.SCHEME_HEADER)).thenReturn("http");

    IdeForwardedRequest forwarded = IdeForwardedRequest.of(req, "/src/app.ts");
    assertEquals("/src/app.ts", forwarded.getRequestURI());
    assertNull(forwarded.getQueryString());
    assertFalse(forwarded.isSecure());
  }
}
