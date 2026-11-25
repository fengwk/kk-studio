package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Account HTTP contract exercised against the shared Testcontainers PostgreSQL database. */
class StudioAccountHttpIntegrationTest extends WebPostgresTestSupport {
  private static final String ORIGIN = "http://localhost";
  private static final String ADMIN = WebAccountTestFixture.ADMIN_USERNAME;
  private static final String PASSWORD = WebAccountTestFixture.ADMIN_PASSWORD;

  @LocalServerPort private int port;
  @Autowired private WebApplicationContext webContext;
  @Autowired private StudioAccountFilter accountFilter;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper mapper;
  private MockMvc mvc;

  @BeforeEach
  void setUpMvc() {
    mvc = MockMvcBuilders.webAppContextSetup(webContext).addFilters(accountFilter).build();
  }

  @Test
  void incompleteOrBlankLoginIsAlwaysUnauthorized() throws Exception {
    for (String body :
        new String[] {
          "",
          "null",
          "{}",
          "{\"username\":null,\"password\":\"password-123\"}",
          "{\"username\":\" \",\"password\":\"password-123\"}",
          "{\"username\":\"" + ADMIN + "\",\"password\":null}",
          "{\"username\":\"" + ADMIN + "\",\"password\":\" \"}"
        }) {
      mvc.perform(
              post("/api/auth/login").with(origin()).contentType("application/json").content(body))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.reason").value("INVALID_CREDENTIALS"));
    }
  }

  @Test
  void invalidUserBodiesHaveStableValidationReason() throws Exception {
    var admin = login(ADMIN, PASSWORD).getResponse().getCookie("studio_session");
    for (String body :
        new String[] {
          "",
          "null",
          "{}",
          "{\"username\":\"valid-user\",\"password\":\"password-123\"}",
          "{\"username\":\"Invalid\",\"password\":\"password-123\",\"role\":\"USER\"}",
          "{\"username\":\"valid-user\",\"password\":\"short\",\"role\":\"USER\"}",
          "{\"username\":\"valid-user\",\"password\":\"password-123\",\"role\":\"OWNER\"}"
        }) {
      mvc.perform(
              post("/api/users")
                  .with(origin())
                  .cookie(admin)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.reason").value("INVALID_ARGUMENT"));
    }
    var id =
        mapper
            .readTree(createUser(admin, "patch-user", "USER").getResponse().getContentAsString())
            .get("id")
            .asText();
    for (String body :
        new String[] {
          "",
          "null",
          "{}",
          "{\"username\":\"\"}",
          "{\"password\":\"short\"}",
          "{\"role\":\"OWNER\"}",
          "{\"enabled\":\"false\"}",
          "{\"username\":null,\"enabled\":true}"
        }) {
      mvc.perform(
              patch("/api/users/{id}", id)
                  .with(origin())
                  .cookie(admin)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.reason").value("INVALID_ARGUMENT"));
    }
  }

  @Test
  void loginFailureDisabledExpiredPasswordAndLogoutLifecycle() throws Exception {
    mvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.reason").value("NOT_AUTHENTICATED"));
    mvc.perform(
            post("/api/auth/login")
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"username\":\"" + ADMIN + "\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.reason").value("INVALID_CREDENTIALS"));
    MvcResult login = login(ADMIN, PASSWORD);
    String cookie = sessionCookie(login);
    mvc.perform(get("/api/auth/me").cookie(login.getResponse().getCookie("studio_session")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value(ADMIN));
    mvc.perform(
            post("/api/auth/logout")
                .with(origin())
                .cookie(login.getResponse().getCookie("studio_session")))
        .andExpect(status().isNoContent())
        .andExpect(cookie().exists("studio_session"));
    mvc.perform(get("/api/auth/me").cookie(MockCookie.parse("studio_session=" + cookie)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void websocketRequiresSessionAndExactLoopbackOriginAndRevokesLoggedOutSession() throws Exception {
    MvcResult login = login(ADMIN, PASSWORD);
    String cookie = sessionCookie(login);
    String endpoint = "ws://127.0.0.1:" + port + "/ws/events";
    HttpClient client = HttpClient.newHttpClient();

    assertHandshakeRejected(client, endpoint, null, "http://127.0.0.1:" + port);
    assertHandshakeRejected(
        client, endpoint, "studio_session=" + cookie, "http://localhost:" + port);

    FrameCollector collector = new FrameCollector();
    WebSocket socket =
        client
            .newWebSocketBuilder()
            .header("Cookie", "studio_session=" + cookie)
            .header("Origin", "http://127.0.0.1:" + port)
            .buildAsync(URI.create(endpoint), collector)
            .get(10, TimeUnit.SECONDS);
    mvc.perform(
            post("/api/auth/logout")
                .with(origin())
                .cookie(login.getResponse().getCookie("studio_session")))
        .andExpect(status().isNoContent());
    socket
        .sendText("{\"type\":\"subscribe\",\"resource\":{\"kind\":\"interactions\"}}", true)
        .get(10, TimeUnit.SECONDS);
    assertEquals(4001, collector.nextCloseCode());
  }

  private void assertHandshakeRejected(
      HttpClient client, String endpoint, String cookie, String origin) throws Exception {
    var builder = client.newWebSocketBuilder().header("Origin", origin);
    if (cookie != null) {
      builder.header("Cookie", cookie);
    }
    try {
      builder.buildAsync(URI.create(endpoint), new FrameCollector()).get(10, TimeUnit.SECONDS);
      throw new AssertionError("WebSocket handshake should have been rejected");
    } catch (ExecutionException expected) {
      assertNotNull(expected.getCause());
    }
  }

  private static final class FrameCollector implements WebSocket.Listener {
    private final BlockingQueue<Integer> closeCodes = new LinkedBlockingQueue<>();

    @Override
    public void onOpen(WebSocket socket) {
      socket.request(Long.MAX_VALUE);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
      closeCodes.add(statusCode);
      return CompletableFuture.completedFuture(null);
    }

    private int nextCloseCode() throws Exception {
      Integer code = closeCodes.poll(10, TimeUnit.SECONDS);
      assertNotNull(code, "expected a WebSocket close frame");
      return code;
    }
  }

  @Test
  void expiredSessionsAndPasswordChangeRevokeOtherSessions() throws Exception {
    MvcResult expiredLogin = login(ADMIN, PASSWORD);
    Cookie expiredCookie = expiredLogin.getResponse().getCookie("studio_session");
    jdbc.sql("update login_session set expires_at = now() - interval '1 second'").update();
    mvc.perform(get("/api/auth/me").cookie(expiredCookie)).andExpect(status().isUnauthorized());

    MvcResult current = login(ADMIN, PASSWORD);
    MvcResult other = login(ADMIN, PASSWORD);
    Cookie currentCookie = current.getResponse().getCookie("studio_session");
    Cookie otherCookie = other.getResponse().getCookie("studio_session");
    mvc.perform(
            put("/api/auth/password")
                .with(origin())
                .cookie(currentCookie)
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\""
                        + PASSWORD
                        + "\",\"newPassword\":\"new-password-123\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/auth/me").cookie(currentCookie)).andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").cookie(otherCookie)).andExpect(status().isUnauthorized());
  }

  @Test
  void originAuthorizationAndDirectorySummaries() throws Exception {
    MvcResult admin = login(ADMIN, PASSWORD);
    var adminCookie = admin.getResponse().getCookie("studio_session");
    mvc.perform(post("/api/auth/logout").cookie(adminCookie))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.reason").value("INVALID_ORIGIN"));
    MvcResult user = createUser(adminCookie, "http-user", "USER");
    String userCookieValue = sessionCookie(login("http-user", "password-123"));
    var userCookie = MockCookie.parse("studio_session=" + userCookieValue);
    for (String route :
        new String[] {
          "settings",
          "config-sync",
          "plugins",
          "providers",
          "mcp-servers",
          "skill-packages",
          "agents",
          "models",
          "environments"
        }) {
      mvc.perform(get("/api/platform/" + route).cookie(userCookie))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.reason").value("ADMIN_REQUIRED"));
    }
    mvc.perform(get("/api/users").cookie(userCookie))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.reason").value("ADMIN_REQUIRED"));
    for (String route : new String[] {"agent", "model", "environment"}) {
      mvc.perform(get("/api/platform/" + route + "-summaries").cookie(userCookie))
          .andExpect(status().isOk());
    }
    JsonNode userNode = mapper.readTree(user.getResponse().getContentAsString());
    String id = userNode.get("id").asText();
    mvc.perform(
            patch("/api/users/{id}", id)
                .with(origin())
                .cookie(adminCookie)
                .contentType("application/json")
                .content("{\"enabled\":false}"))
        .andDo(
            result -> {
              if (result.getResponse().getStatus() != 200) {
                throw new AssertionError(
                    result.getResponse().getContentAsString(), result.getResolvedException());
              }
            })
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").cookie(userCookie)).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/login")
                .with(origin())
                .contentType("application/json")
                .content("{\"username\":\"http-user\",\"password\":\"password-123\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.reason").value("INVALID_CREDENTIALS"));
  }

  @Test
  void concurrentAdminDemotionsNeverRemoveLastAdministrator() throws Exception {
    MvcResult admin = login(ADMIN, PASSWORD);
    var adminCookie = admin.getResponse().getCookie("studio_session");
    MvcResult second = createUser(adminCookie, "second-admin", "ADMIN");
    String secondId = mapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
    var secondCookie =
        login("second-admin", "password-123").getResponse().getCookie("studio_session");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<Integer> a = pool.submit(() -> concurrentDemote(secondCookie, secondId, ready, start));
      Future<Integer> b =
          pool.submit(
              () ->
                  concurrentDemote(
                      adminCookie,
                      jdbc.sql("select id from studio_user where username = :username")
                          .param("username", ADMIN)
                          .query(UUID.class)
                          .single()
                          .toString(),
                      ready,
                      start));
      if (!ready.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("requests did not become ready");
      }
      start.countDown();
      int statusA = a.get(30, TimeUnit.SECONDS);
      int statusB = b.get(30, TimeUnit.SECONDS);
      if (!((statusA == 200 && statusB == 409) || (statusA == 409 && statusB == 200))) {
        throw new AssertionError("unexpected concurrent responses: " + statusA + ", " + statusB);
      }
    }
    long admins =
        jdbc.sql("select count(*) from studio_user where role = 'ADMIN' and disabled_at is null")
            .query(Long.class)
            .single();
    if (admins != 1) {
      throw new AssertionError("last administrator was removed");
    }
  }

  private int concurrentDemote(Cookie cookie, String id, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("start signal timeout");
    }
    return mvc.perform(
            patch("/api/users/{id}", id)
                .with(origin())
                .cookie(cookie)
                .contentType("application/json")
                .content("{\"role\":\"USER\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private MvcResult login(String username, String password) throws Exception {
    return mvc.perform(
            post("/api/auth/login")
                .with(origin())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(Map.of("username", username, "password", password))))
        .andExpect(status().isOk())
        .andExpect(cookie().httpOnly("studio_session", true))
        .andReturn();
  }

  private MvcResult createUser(Cookie cookie, String username, String role) throws Exception {
    return mvc.perform(
            post("/api/users")
                .with(origin())
                .cookie(cookie)
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of("username", username, "password", "password-123", "role", role))))
        .andExpect(status().isOk())
        .andReturn();
  }

  private static String sessionCookie(MvcResult result) {
    return result.getResponse().getCookie("studio_session").getValue();
  }

  private static RequestPostProcessor origin() {
    return request -> {
      request.addHeader("Origin", ORIGIN);
      return request;
    };
  }
}
