package fun.fengwk.kkstudio.web.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.http.ClusterEnvironmentIdeInternalController;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
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
            .setControllerAdvice(new ProblemAdvice(new MessageService()))
            .build();
  }

  /** 新端点也必须验证包含原始 query 的签名并只交付本地 owner。 */
  @Test
  void newEndpointsAuthenticateAndDelegate() throws Exception {
    when(clusterNodeRegistry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(NODE_B)));
    String directories = IdeInternalPaths.environmentPrefix(ENV) + "/directories?path=%2Fa%2Bb";
    mockMvc
        .perform(
            get(URI.create(directories))
                .headers(mockHeaders(authentication.sign("GET", directories, NODE_B))))
        .andExpect(status().isOk());
    verify(localEnvironmentIdeService).listDirectories(ENV, "/a+b");

    String mobile = IdeInternalPaths.mobileLayoutPath(ENV, VIEW);
    mockMvc
        .perform(
            post(mobile)
                .headers(mockHeaders(authentication.sign("POST", mobile, NODE_B)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentId\":\"11111111-1111-1111-1111-111111111111\",\"bindingEpoch\":\"1\"}"))
        .andExpect(status().isOk());
    verify(localEnvironmentIdeService).collapseMobile(eq(ENV), eq(VIEW), any());
  }

  /** 远端关闭的签名路径必须与实际内部 controller 路由一致。 */
  @Test
  void authenticatedCloseUsesViewsPath() throws Exception {
    when(clusterNodeRegistry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(NODE_B)));
    String path = IdeInternalPaths.closePath(ENV, VIEW);
    mockMvc
        .perform(
            post(path)
                .headers(mockHeaders(authentication.sign("POST", path, NODE_B)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentId\":\"11111111-1111-1111-1111-111111111111\",\"bindingEpoch\":\"1\",\"discard\":false}"))
        .andExpect(status().isOk());
    verify(localEnvironmentIdeService).closeView(eq(ENV), eq(VIEW), any());
  }

  /** 客户端契约路径经真实认证和 controller 路由解析，绑定字段不能丢失。 */
  @Test
  void boundCommandPathsMatchClientContract() throws Exception {
    when(clusterNodeRegistry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(NODE_B)));
    String body =
        "{\"documentId\":\"11111111-1111-1111-1111-111111111111\",\"bindingEpoch\":\"1\"}";
    String directory = IdeInternalPaths.directoryPath(ENV, VIEW);
    mockMvc
        .perform(
            put(directory)
                .headers(mockHeaders(authentication.sign("PUT", directory, NODE_B)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.substring(0, body.length() - 1) + ",\"directory\":\"/next\"}"))
        .andExpect(status().isOk());
    String terminal = IdeInternalPaths.terminalPath(ENV, VIEW);
    mockMvc
        .perform(
            post(terminal)
                .headers(mockHeaders(authentication.sign("POST", terminal, NODE_B)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    String mobile = IdeInternalPaths.mobileLayoutPath(ENV, VIEW);
    mockMvc
        .perform(
            post(mobile)
                .headers(mockHeaders(authentication.sign("POST", mobile, NODE_B)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    verify(localEnvironmentIdeService)
        .changeDirectory(
            eq(ENV),
            eq(VIEW),
            argThat(
                dto ->
                    "/next".equals(dto.getDirectory())
                        && Long.valueOf(1L).equals(dto.getBindingEpoch())));
    verify(localEnvironmentIdeService)
        .focusTerminal(
            eq(ENV), eq(VIEW), argThat(dto -> Long.valueOf(1L).equals(dto.getBindingEpoch())));
    verify(localEnvironmentIdeService)
        .collapseMobile(
            eq(ENV), eq(VIEW), argThat(dto -> Long.valueOf(1L).equals(dto.getBindingEpoch())));
  }

  /** 客户端通过真实 controller 与 advice 收到 problem，只允许已知取消原因穿透。 */
  @Test
  void cancellationReasonSurvivesControllerJsonAndClusterClient() throws Exception {
    when(clusterNodeRegistry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(NODE_B)));
    doThrow(DomainException.validationReason("IDE_CLOSE_CANCELLED", "cancelled"))
        .doThrow(DomainException.validation("other_resource", "cancelled"))
        .doThrow(DomainException.unavailable("IDE_UNAVAILABLE", "cancelled"))
        .when(localEnvironmentIdeService)
        .closeView(eq(ENV), eq(VIEW), any());
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            HttpHeaders headers = new HttpHeaders();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values));
            MvcResult result =
                mockMvc
                    .perform(
                        post(exchange.getRequestURI())
                            .headers(headers)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(exchange.getRequestBody().readAllBytes()))
                    .andReturn();
            byte[] response = result.getResponse().getContentAsByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(result.getResponse().getStatus(), response.length);
            exchange.getResponseBody().write(response);
          } catch (Exception error) {
            throw new IOException(error);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      IdeClusterClient client =
          new IdeClusterClient(
              HttpClient.newHttpClient(),
              JsonMapper.builder().build(),
              authentication,
              Duration.ofSeconds(3),
              65536);
      ClusterOwnerRoute owner =
          ClusterOwnerRoute.remote(
              NODE_B, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      EnvironmentIdeCloseViewDTO request = new EnvironmentIdeCloseViewDTO();
      request.setDocumentId("11111111-1111-1111-1111-111111111111");
      request.setBindingEpoch(1L);
      request.setDiscard(false);
      DomainException cancelled =
          assertThrows(DomainException.class, () -> client.closeView(ENV, owner, VIEW, request));
      assertEquals("IDE_CLOSE_CANCELLED", cancelled.reason());
      assertEquals("validation", cancelled.code().code());
      DomainException other =
          assertThrows(DomainException.class, () -> client.closeView(ENV, owner, VIEW, request));
      assertEquals("environment", other.resource());
      assertThrows(DomainException.class, () -> client.closeView(ENV, owner, VIEW, request));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void missingAuthenticationIsRejectedBeforeAnyLocalSideEffect() throws Exception {
    mockMvc
        .perform(
            post(IdeInternalPaths.viewsPath(ENV))
                .contentType(MediaType.APPLICATION_JSON)
                .content(openViewBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"))
        .andExpect(jsonPath("$.detail").value("internal authentication failed"));
    verifyNoInteractions(localEnvironmentIdeService);
    verifyNoInteractions(clusterNodeRegistry);
  }

  @Test
  void wrongAuthenticationTargetIsRejectedBeforeAnyLocalSideEffect() throws Exception {
    Map<String, String> headers =
        authentication.sign("POST", IdeInternalPaths.viewsPath(ENV), NODE_A);

    mockMvc
        .perform(
            post(IdeInternalPaths.viewsPath(ENV))
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
        .thenReturn(Optional.of(ClusterOwnerRoute.remote(NODE_A, URI.create("http://a.internal"))));

    mockMvc
        .perform(
            post(IdeInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(headers))
                .contentType(MediaType.APPLICATION_JSON)
                .content(openViewBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("unavailable"));
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedNonOwnerWithOversizedBodyIsRejectedBeforeBodyIsRead() throws Exception {
    when(clusterNodeRegistry.route(ENV))
        .thenReturn(Optional.of(ClusterOwnerRoute.remote(NODE_A, URI.create("http://a.internal"))));

    mockMvc
        .perform(
            post(IdeInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(validHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pad\":\"" + "a".repeat(20 * 1024) + "\"}"))
        .andExpect(status().isConflict());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedLocalOwnerDelegatesAndReturnsTheViewDto() throws Exception {
    Map<String, String> headers = validHeaders();
    when(clusterNodeRegistry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(NODE_B)));
    EnvironmentIdeViewDTO dto = new EnvironmentIdeViewDTO();
    dto.setViewId(VIEW);
    dto.setEnvironmentId(ENV.toString());
    dto.setDirectory("/home/dev/project");
    when(localEnvironmentIdeService.openView(eq(ENV), any())).thenReturn(dto);

    mockMvc
        .perform(
            post(IdeInternalPaths.viewsPath(ENV))
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
            post(IdeInternalPaths.viewsPath(ENV))
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
            post(IdeInternalPaths.viewsPath(ENV))
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .content(openViewBody()))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedOversizedBodyIsRejectedWithPayloadTooLarge() throws Exception {
    when(clusterNodeRegistry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(NODE_B)));

    mockMvc
        .perform(
            post(IdeInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(validHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pad\":\"" + "a".repeat(20 * 1024) + "\"}"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("validation"));
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedMalformedBodyIsRejectedWithBadRequest() throws Exception {
    when(clusterNodeRegistry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(NODE_B)));

    mockMvc
        .perform(
            post(IdeInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(validHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    verifyNoInteractions(localEnvironmentIdeService);
  }

  @Test
  void authenticatedUnknownFieldBodyIsRejectedWithBadRequest() throws Exception {
    when(clusterNodeRegistry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(NODE_B)));

    mockMvc
        .perform(
            post(IdeInternalPaths.viewsPath(ENV))
                .headers(mockHeaders(validHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"viewId\":\"" + VIEW + "\",\"directory\":\"/d\",\"extra\":1}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(localEnvironmentIdeService);
  }

  private Map<String, String> validHeaders() {
    return authentication.sign("POST", IdeInternalPaths.viewsPath(ENV), NODE_B);
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
