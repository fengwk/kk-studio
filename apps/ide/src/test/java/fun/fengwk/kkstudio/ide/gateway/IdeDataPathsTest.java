package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.UUID;

class IdeDataPathsTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void daemonPathValidation() {
    assertEquals("/ide/" + VIEW + "/1/workbench", IdeDataPaths.daemonPath(VIEW, 1L, "/workbench"));
    assertThrows(IllegalArgumentException.class, () -> IdeDataPaths.daemonPath(VIEW, 0L, "/x"));
    assertThrows(
        IllegalArgumentException.class, () -> IdeDataPaths.daemonPath(VIEW, 1L, "no-slash"));
  }

  @Test
  void internalDataPathsFormatting() {
    String httpPath = IdeDataPaths.dataHttpPath(ENV, VIEW, "/src/main.ts?a=1&b=2");
    assertTrue(httpPath.contains("/internal/ide/environments/" + ENV + "/data-http"));
    assertTrue(httpPath.contains("viewId=" + VIEW));
    assertTrue(httpPath.contains("path="));

    String wsPath = IdeDataPaths.dataWebSocketPath(ENV, VIEW, "/ws");
    assertTrue(wsPath.contains("/internal/ide/environments/" + ENV + "/data-ws"));
    assertTrue(wsPath.contains("viewId=" + VIEW));
  }

  @Test
  void isSafeViewPathValidation() {
    assertTrue(IdeDataPaths.isSafeViewPath("/"));
    assertTrue(IdeDataPaths.isSafeViewPath("/workbench"));
    assertTrue(IdeDataPaths.isSafeViewPath("/src/app.ts?x=1"));

    assertFalse(IdeDataPaths.isSafeViewPath(null));
    assertFalse(IdeDataPaths.isSafeViewPath("no-slash"));
    assertFalse(IdeDataPaths.isSafeViewPath("//double-slash"));
    assertFalse(IdeDataPaths.isSafeViewPath("/path/../escape"));
    assertFalse(IdeDataPaths.isSafeViewPath("/path/./dot"));
    assertFalse(IdeDataPaths.isSafeViewPath("/path\\backslash"));
    assertFalse(IdeDataPaths.isSafeViewPath("/path/%2e%2e/escape"));
    assertFalse(IdeDataPaths.isSafeViewPath("/path/%2f/encoded"));
    assertFalse(IdeDataPaths.isSafeViewPath("/path/%5c/encoded"));
    assertFalse(IdeDataPaths.isSafeViewPath("/path/%00/null"));
  }

  @Test
  void viewBaseFormatting() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContextPath()).thenReturn("");
    assertEquals("/ide/" + ENV + "/" + VIEW + "/", IdeDataPaths.viewBase(req, ENV, VIEW));
    assertEquals("/ide/" + ENV + "/", IdeDataPaths.viewBase(req, ENV, null));

    when(req.getContextPath()).thenReturn("/context");
    assertEquals("/context/ide/" + ENV + "/" + VIEW + "/", IdeDataPaths.viewBase(req, ENV, VIEW));
  }

  @Test
  void rebaseLocationHandling() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContextPath()).thenReturn("");

    assertEquals(
        "https://external.com/path",
        IdeDataPaths.rebaseLocation("https://external.com/path", req, ENV, VIEW));
    assertEquals(
        "/ide/" + ENV + "/" + VIEW + "/relative/path",
        IdeDataPaths.rebaseLocation("/relative/path", req, ENV, VIEW));
    assertEquals(
        "/ide/" + ENV + "/" + VIEW + "/dashboard",
        IdeDataPaths.rebaseLocation("/__kk/ide/view/" + VIEW + "/1/dashboard", req, ENV, VIEW));
    assertEquals(
        "/ide/" + ENV + "/" + VIEW + "/",
        IdeDataPaths.rebaseLocation("/__kk/ide/view/" + VIEW + "/1/", req, ENV, VIEW));
    assertEquals(
        "/ide/" + ENV + "/" + VIEW + "/?workspace=a",
        IdeDataPaths.rebaseLocation("/__kk/ide/view/" + VIEW + "/1?workspace=a", req, ENV, VIEW));
    assertEquals(null, IdeDataPaths.rebaseLocation(null, req, ENV, VIEW));
    assertEquals("  ", IdeDataPaths.rebaseLocation("  ", req, ENV, VIEW));
    assertEquals("//host/path", IdeDataPaths.rebaseLocation("//host/path", req, ENV, VIEW));
    assertEquals(
        "/ide/" + ENV + "/" + VIEW + "/__kk/ide/view/" + VIEW + "/invalid-epoch/path",
        IdeDataPaths.rebaseLocation(
            "/__kk/ide/view/" + VIEW + "/invalid-epoch/path", req, ENV, VIEW));
    assertEquals(
        "/ide/" + ENV + "/" + VIEW + "/",
        IdeDataPaths.rebaseLocation("/__kk/ide/view/" + VIEW + "/1", req, ENV, VIEW));
  }

  @Test
  void rebaseWorkerScopeHandling() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContextPath()).thenReturn("");
    when(req.getRequestURI()).thenReturn("/ide/" + ENV + "/" + VIEW + "/root");

    String base = "/ide/" + ENV + "/" + VIEW + "/";
    assertEquals(base, IdeDataPaths.rebaseWorkerScope("invalid#fragment", req, ENV, VIEW));
    assertEquals(base, IdeDataPaths.rebaseWorkerScope("https://other.com", req, ENV, VIEW));
    assertEquals(
        base, IdeDataPaths.rebaseWorkerScope("/__kk/ide/view/" + VIEW + "/1/", req, ENV, VIEW));
    assertEquals(base, IdeDataPaths.rebaseWorkerScope("scope?query=1", req, ENV, VIEW));
    assertEquals("/ide/" + ENV + "/", IdeDataPaths.rebaseWorkerScope("scope", req, ENV, null));
    assertEquals(
        base + "sub/worker.js", IdeDataPaths.rebaseWorkerScope("./sub/worker.js", req, ENV, VIEW));
    assertEquals(base, IdeDataPaths.rebaseWorkerScope("/outside/view", req, ENV, VIEW));
    assertEquals(base, IdeDataPaths.rebaseWorkerScope(":bad-uri", req, ENV, VIEW));
  }
}
