package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;

import java.util.Optional;
import java.util.UUID;

class StudioAccountFilterTest {
  private final StudioAccountService accounts = mock(StudioAccountService.class);
  private final StudioAccountFilter filter =
      new StudioAccountFilter(accounts, mock(HandlerExceptionResolver.class));

  @ParameterizedTest
  @CsvSource({
    "/api/platform/config-sync,200",
    "/api/platform/config-sync/export,200",
    "/api/platform/config-sync/import,400",
    "/api/platform/config-sync/import,409",
    "/api/platform/config-sync/import,500",
    "/api/platform/config-sync/check-import,200",
    "/api/platform/config-sync/check-import,400",
    "/api/platform/settings,200"
  })
  void apiPolicyIsPresentBeforeDownstreamProcessing(String path, int status) throws Exception {
    authorizeAdmin();
    var request = new MockHttpServletRequest("POST", "/studio" + path);
    request.setContextPath("/studio");
    request.addHeader("Origin", "http://localhost");
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
          response.setStatus(status);
        });
    assertEquals(status, response.getStatus());
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
  }

  @Test
  void setsNoStoreOnDeniedAndFailedApiRequests() throws Exception {
    for (var request :
        new MockHttpServletRequest[] {
          new MockHttpServletRequest("POST", "/api/auth/login"),
          new MockHttpServletRequest("GET", "/api/platform/config-sync")
        }) {
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, (req, res) -> fail("Expected rejection"));
      assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }
    authorizeAdmin();
    var request = new MockHttpServletRequest("GET", "/api/platform/config-sync;parameter=1");
    var response = new MockHttpServletResponse();
    assertThrows(
        ServletException.class,
        () ->
            filter.doFilter(
                request,
                response,
                (req, res) -> {
                  throw new ServletException("Request failed");
                }));
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
  }

  @Test
  void leavesIdeAndWebSocketCachePolicyToTheirHandlers() throws Exception {
    authorizeAdmin();
    for (String path : new String[] {"/ide/workbench.js", "/ws/events"}) {
      var request = new MockHttpServletRequest("GET", path);
      request.addHeader("Origin", "http://localhost");
      var response = new MockHttpServletResponse();
      filter.doFilter(
          request,
          response,
          (req, res) -> {
            assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=60");
          });
      assertEquals("private, max-age=60", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }
  }

  private void authorizeAdmin() {
    when(accounts.authenticate(null))
        .thenReturn(Optional.of(new StudioPrincipal(UUID.randomUUID(), "admin", StudioRole.ADMIN)));
  }
}
