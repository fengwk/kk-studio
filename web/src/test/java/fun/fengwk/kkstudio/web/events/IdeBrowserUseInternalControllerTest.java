package fun.fengwk.kkstudio.web.events;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUse;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUsePaths;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseView;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 意图：内部 browser-use 查询先认证；POST 精确意图、GET 只返回当前已绑定证明，缺失为 404。 */
class IdeBrowserUseInternalControllerTest {

  private static final UUID NODE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID OTHER_NODE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CONNECTION = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final UUID DOCUMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final UUID VIEW = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID BOUND_VIEW = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final UUID CLAIM = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";

  private HmacClusterInternalAuthentication authentication;
  private IdeBrowserUseRegistry registry;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    authentication =
        new HmacClusterInternalAuthentication(
            SECRET, NODE, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    registry = new IdeBrowserUseRegistry();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new IdeBrowserUseInternalController(authentication, registry))
            .build();
  }

  @Test
  void exactIntentPostRequiresAuthenticationBeforeReadingTheBody() throws Exception {
    mockMvc
        .perform(
            post(IdeBrowserUsePaths.INTERNAL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void exactIntentPostMatchesOnlyTheSameLiveDocumentAndClaim() throws Exception {
    long generation = registerUsage(1, intent());
    String path = IdeBrowserUsePaths.intentPath();

    mockMvc
        .perform(
            post(path)
                .headers(headers(authentication.sign("POST", path, NODE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(true));

    mockMvc
        .perform(
            post(path)
                .headers(headers(authentication.sign("POST", path, NODE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody().replace(CLAIM.toString(), UUID.randomUUID().toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(false));

    registry.unregister(identity(), generation);
    mockMvc
        .perform(
            post(path)
                .headers(headers(authentication.sign("POST", path, NODE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(false));
  }

  @Test
  void activeBoundProofsGetReturnsOnlyBoundViews() throws Exception {
    registerUsage(
        1, intent(), new IdeBrowserUseView(ENVIRONMENT, BOUND_VIEW, UUID.randomUUID(), 9L));
    String path = IdeBrowserUsePaths.activeProofsPath(identity(), DOCUMENT);

    mockMvc
        .perform(get(path).headers(headers(authentication.sign("GET", path, NODE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventNodeId").value(NODE.toString()))
        .andExpect(jsonPath("$.connectionId").value(CONNECTION.toString()))
        .andExpect(jsonPath("$.documentId").value(DOCUMENT.toString()))
        .andExpect(jsonPath("$.revision").value("1"))
        .andExpect(jsonPath("$.views.length()").value(1))
        .andExpect(jsonPath("$.views[0].viewId").value(BOUND_VIEW.toString()))
        .andExpect(jsonPath("$.views[0].bindingEpoch").value("9"));
  }

  @Test
  void unboundIntentHasNoActiveProofsAndUnboundGetIs404() throws Exception {
    registerUsage(1, intent());
    String path = IdeBrowserUsePaths.activeProofsPath(identity(), DOCUMENT);

    mockMvc
        .perform(get(path).headers(headers(authentication.sign("GET", path, NODE))))
        .andExpect(status().isNotFound());
  }

  @Test
  void duplicateOrUnknownQueryParametersAreRejected() throws Exception {
    registerUsage(1, new IdeBrowserUseView(ENVIRONMENT, BOUND_VIEW, UUID.randomUUID(), 9L));
    String base = IdeBrowserUsePaths.activeProofsPath(identity(), DOCUMENT);
    String duplicate = base.replace("&connectionId=", "&eventNodeId=" + NODE + "&connectionId=");
    for (String path : List.of(duplicate, base + "&extra=true")) {
      mockMvc
          .perform(get(path).headers(headers(authentication.sign("GET", path, NODE))))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void postRejectsQueryParametersAndTrailingBodyTokens() throws Exception {
    registerUsage(1, intent());
    String path = IdeBrowserUsePaths.INTERNAL_PATH + "?extra=true";
    mockMvc
        .perform(
            post(path)
                .headers(headers(authentication.sign("POST", path, NODE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody()))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post(IdeBrowserUsePaths.INTERNAL_PATH)
                .headers(
                    headers(authentication.sign("POST", IdeBrowserUsePaths.INTERNAL_PATH, NODE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody() + " trailing"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void wrongTargetAndUnknownFieldsAreRejected() throws Exception {
    String path = IdeBrowserUsePaths.intentPath();
    mockMvc
        .perform(
            post(path)
                .headers(headers(authentication.sign("POST", path, OTHER_NODE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody()))
        .andExpect(status().isUnauthorized());

    registerUsage(1, intent());
    mockMvc
        .perform(
            post(path)
                .headers(headers(authentication.sign("POST", path, NODE)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody().replace("}", ",\"extra\":true}")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unauthenticatedOversizedBodyIsRejectedWithoutParsing() throws Exception {
    mockMvc
        .perform(
            post(IdeBrowserUsePaths.INTERNAL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pad\":\"" + "a".repeat(IdeBrowserUsePaths.MAX_BODY_BYTES + 1) + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  private long registerUsage(long revision, IdeBrowserUseView... views) {
    long generation = registry.register(identity());
    registry.accept(identity(), generation, new IdeBrowserUse(DOCUMENT, revision, List.of(views)));
    return generation;
  }

  private String intentBody() {
    return """
        {"eventNodeId":"%s","connectionId":"%s","documentId":"%s","environmentId":"%s",
         "viewId":"%s","claimId":"%s"}
        """
        .formatted(NODE, CONNECTION, DOCUMENT, ENVIRONMENT, VIEW, CLAIM);
  }

  private static IdeBrowserUseView intent() {
    return new IdeBrowserUseView(ENVIRONMENT, VIEW, CLAIM, null);
  }

  private static ConnectionIdentity identity() {
    return new ConnectionIdentity(NODE, CONNECTION);
  }

  private static HttpHeaders headers(Map<String, String> values) {
    HttpHeaders headers = new HttpHeaders();
    values.forEach(headers::add);
    return headers;
  }
}
