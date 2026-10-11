package fun.fengwk.kkstudio.web.environment.cluster;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 意图：内部 controller 必须在任何本地 delegate 副作用之前完成认证与 owner 核对——缺认证头、目标不符返回 401；本节点非 READY owner 返回
 * 409；两者都不得触发本地命令。认证通过且本节点即 owner 时才委托本地 delegate 并返回 ViewDTO。
 */
class ClusterEnvironmentIdeInternalControllerTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String VIEW = "11111111-2222-3333-4444-555555555555";
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";

  private EnvironmentIdeServiceImpl localEnvironmentIdeService;
  private ClusterNodeRegistry clusterNodeRegistry;
  private HmacClusterInternalAuthentication authentication;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    localEnvironmentIdeService = mock(EnvironmentIdeServiceImpl.class);
    clusterNodeRegistry = mock(ClusterNodeRegistry.class);
    authentication =
        new HmacClusterInternalAuthentication(
            SECRET, NODE_B, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ClusterEnvironmentIdeInternalController(
                    localEnvironmentIdeService, clusterNodeRegistry, authentication))
            .build();
  }

  @Test
  void missingAuthenticationIsRejectedBeforeAnyLocalSideEffect() throws Exception {
    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .contentType(MediaType.APPLICATION_JSON)
                .content(openViewBody()))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(localEnvironmentIdeService);
    verifyNoInteractions(clusterNodeRegistry);
  }

  @Test
  void wrongAuthenticationTargetIsRejectedBeforeAnyLocalSideEffect() throws Exception {
    Map<String, String> headers =
        authentication.sign(
            "POST",
            ClusterInternalPaths.viewsPath(ENV),
            ClusterIdeOwnerRoute.remote(NODE_A, URI.create("http://a.internal")));

    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(headers))
                .contentType(MediaType.APPLICATION_JSON)
                .content(openViewBody()))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void requestForAnotherNodeOwnerIsRejectedBeforeAnyLocalSideEffect() throws Exception {
    Map<String, String> headers = validHeaders();
    when(clusterNodeRegistry.route(ENV))
        .thenReturn(
            Optional.of(ClusterIdeOwnerRoute.remote(NODE_A, URI.create("http://a.internal"))));

    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(headers))
                .contentType(MediaType.APPLICATION_JSON)
                .content(openViewBody()))
        .andExpect(status().isConflict());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedNonOwnerWithOversizedBodyIsRejectedBeforeBodyIsRead() throws Exception {
    when(clusterNodeRegistry.route(ENV))
        .thenReturn(
            Optional.of(ClusterIdeOwnerRoute.remote(NODE_A, URI.create("http://a.internal"))));

    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(validHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pad\":\"" + "a".repeat(20 * 1024) + "\"}"))
        .andExpect(status().isConflict());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedLocalOwnerDelegatesAndReturnsTheViewDto() throws Exception {
    Map<String, String> headers = validHeaders();
    when(clusterNodeRegistry.route(ENV))
        .thenReturn(Optional.of(ClusterIdeOwnerRoute.local(NODE_B)));
    EnvironmentIdeViewDTO dto = new EnvironmentIdeViewDTO();
    dto.setViewId(VIEW);
    dto.setEnvironmentId(ENV.toString());
    dto.setDirectory("/home/dev/project");
    when(localEnvironmentIdeService.openView(eq(ENV), any())).thenReturn(dto);

    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(headers))
                .contentType(MediaType.APPLICATION_JSON)
                .content(openViewBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewId").value(VIEW))
        .andExpect(jsonPath("$.environmentId").value(ENV.toString()));

    verify(localEnvironmentIdeService).openView(eq(ENV), any());
  }

  @Test
  void unauthenticatedOversizedBodyIsRejectedWithoutParsingOrSideEffects() throws Exception {
    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pad\":\"" + "a".repeat(20 * 1024) + "\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(localEnvironmentIdeService);
    verifyNoInteractions(clusterNodeRegistry);
  }

  @Test
  void duplicateAuthenticationHeaderIsRejected() throws Exception {
    HttpHeaders headers = mockHeaders(validHeaders());
    headers.add(
        HmacClusterInternalAuthentication.HEADER_SIGNATURE,
        headers.getFirst(HmacClusterInternalAuthentication.HEADER_SIGNATURE));

    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .content(openViewBody()))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedOversizedBodyIsRejectedWithPayloadTooLarge() throws Exception {
    when(clusterNodeRegistry.route(ENV))
        .thenReturn(Optional.of(ClusterIdeOwnerRoute.local(NODE_B)));

    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(validHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pad\":\"" + "a".repeat(20 * 1024) + "\"}"))
        .andExpect(status().isPayloadTooLarge());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedMalformedBodyIsRejectedWithBadRequest() throws Exception {
    when(clusterNodeRegistry.route(ENV))
        .thenReturn(Optional.of(ClusterIdeOwnerRoute.local(NODE_B)));

    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(validHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedUnknownFieldBodyIsRejectedWithBadRequest() throws Exception {
    when(clusterNodeRegistry.route(ENV))
        .thenReturn(Optional.of(ClusterIdeOwnerRoute.local(NODE_B)));

    mockMvc
        .perform(
            post(ClusterInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(validHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"viewId\":\"" + VIEW + "\",\"directory\":\"/d\",\"extra\":1}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  private Map<String, String> validHeaders() {
    return authentication.sign(
        "POST",
        ClusterInternalPaths.viewsPath(ENV),
        ClusterIdeOwnerRoute.remote(NODE_B, URI.create("http://b.internal")));
  }

  private static HttpHeaders mockHeaders(Map<String, String> headers) {
    HttpHeaders httpHeaders = new HttpHeaders();
    headers.forEach(httpHeaders::add);
    return httpHeaders;
  }

  private static String openViewBody() {
    return "{\"viewId\":\"" + VIEW + "\",\"directory\":\"/home/dev/project\"}";
  }
}
