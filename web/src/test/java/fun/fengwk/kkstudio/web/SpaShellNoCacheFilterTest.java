package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 验证 SPA 外壳在解析之前带上回源验证策略，且不影响 API、构建产物与其它静态文件。 */
class SpaShellNoCacheFilterTest {

  private final SpaShellNoCacheFilter filter = new SpaShellNoCacheFilter();

  @ParameterizedTest
  @ValueSource(strings = {"/", "/index.html", "/chats/123", "/canvas", "/workspace/threads/abc"})
  void marksTheSpaShellBeforeDownstreamProcessing(String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (req, res) -> assertEquals("no-cache", response.getHeader(HttpHeaders.CACHE_CONTROL)));
    assertEquals("no-cache", response.getHeader(HttpHeaders.CACHE_CONTROL));
  }

  @Test
  void leavesNonReadMethodsUntouched() throws Exception {
    for (String method : new String[] {"HEAD", "POST", "PUT", "DELETE"}) {
      MockHttpServletRequest request = new MockHttpServletRequest(method, "/chats/123");
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, (req, res) -> {});
      assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
    }
  }

  @Test
  void matchesInsideAServletContextWithoutDependingOnServletPath() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/studio/canvas");
    request.setContextPath("/studio");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    assertEquals("no-cache", response.getHeader(HttpHeaders.CACHE_CONTROL));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/platform/settings",
        "/api",
        "/actuator/health",
        "/ws/events",
        "/ide/missing",
        "/internal/realtime/payloads/x",
        "/assets/index-abc123.js",
        "/favicon.svg",
        "/plain.txt",
        "/studio-missing.txt"
      })
  void leavesNonShellPathsUntouched(String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
  }
}
