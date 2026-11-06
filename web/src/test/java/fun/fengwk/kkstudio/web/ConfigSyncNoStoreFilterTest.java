package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 验证同步响应在解析和业务处理之前禁止缓存，且不影响其它路由。 */
class ConfigSyncNoStoreFilterTest {

  private final ConfigSyncNoStoreFilter filter = new ConfigSyncNoStoreFilter();

  @ParameterizedTest
  @CsvSource({
    "/api/platform/config-sync,200",
    "/api/platform/config-sync/export,200",
    "/api/platform/config-sync/import,400",
    "/api/platform/config-sync/import,409",
    "/api/platform/config-sync/import,500",
    "/api/platform/config-sync/check-import,200",
    "/api/platform/config-sync/check-import,400"
  })
  void appliesBeforeDownstreamProcessing(String path, int status) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          // 即使请求尚未解析或即将失败，下游入口已带有缓存保护。
          assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
          response.setStatus(status);
        });
    assertEquals(status, response.getStatus());
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
  }

  @Test
  void matchesInsideAServletContextWithoutDependingOnServletPath() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/studio/api/platform/config-sync");
    request.setContextPath("/studio");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/platform/config-%73ync",
        "/api/platform/config-sync;parameter=1",
        "//api/platform/config-sync/import"
      })
  void normalizesEquivalentServletPathsBeforeMatching(String path) throws Exception {
    // URI 编码、路径参数或多余斜杠不能绕过同一同步路由的缓存保护。
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
  }

  @Test
  void establishesProtectionBeforeAnUnhandledFailure() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/platform/config-sync/import");
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThrows(
        ServletException.class,
        () ->
            filter.doFilter(
                request,
                response,
                (req, res) -> {
                  throw new ServletException("request failed");
                }));
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/platform/settings",
        "/api/platform/config-sync-other",
        "/api/platform/config-sync.yaml"
      })
  void leavesUnrelatedAndLookalikeRoutesUntouched(String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
  }
}
