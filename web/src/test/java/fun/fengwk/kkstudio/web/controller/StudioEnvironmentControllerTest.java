package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentEventDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;
import fun.fengwk.kkstudio.web.advice.StudioDomainErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class StudioEnvironmentControllerTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private EnvironmentService environmentService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    environmentService = mock(EnvironmentService.class);

    StudioEnvironmentController controller = new StudioEnvironmentController(environmentService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioDomainErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  /** 意图：验证 GET /api/harness/environments 列表查询返回 200 与卡片列表。 */
  @Test
  void listEnvironmentsReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    card.setStatus("READY");
    when(environmentService.list()).thenReturn(List.of(card));

    mockMvc
        .perform(get("/api/harness/environments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data[0].name").value("dev"))
        .andExpect(jsonPath("$.data[0].skills").doesNotExist());
  }

  /** 意图：验证 GET /api/harness/environments/{environmentId} 查询已存在的环境返回 200 与对应的卡片信息。 */
  @Test
  void getEnvironmentReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.name").value("dev"))
        .andExpect(jsonPath("$.data.skills").doesNotExist());
  }

  /**
   * 意图：验证当服务层抛出 AiResourceNotFoundException 时，由统一 StudioDomainErrorAdvice 映射为 HTTP 404 与
   * RESOURCE_NOT_FOUND (code="resource_not_found") 错误信封，而不是泄露为 500。
   */
  @Test
  void getMissingEnvironmentReturnsNotFound() throws Exception {
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID))))
        .thenThrow(new AiResourceNotFoundException("environment"));

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("resource_not_found"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail").value("environment not found"));
  }

  /** 意图：验证 POST /api/harness/environments 创建环境成功返回 HTTP 201 Created，且携带凭据的响应禁止缓存。 */
  @Test
  void createEnvironmentReturnsCreated() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("new-env");
    card.setRegistrationToken("secret-token");
    when(environmentService.create(any(EnvironmentCreateDTO.class))).thenReturn(card);

    mockMvc
        .perform(
            post("/api/harness/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-env\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").value("secret-token"));
  }

  /** 意图：验证 GET /api/harness/environments/{id}/events 按时间正序返回事件窗口。 */
  @Test
  void listEnvironmentEventsReturnsChronologicalWindow() throws Exception {
    EnvironmentEventDTO connecting = new EnvironmentEventDTO();
    connecting.setTime(Instant.parse("2026-09-21T06:00:00Z"));
    connecting.setLevel("INFO");
    connecting.setType("CONNECTING");
    connecting.setMessage("daemon connection accepted");
    EnvironmentEventDTO failed = new EnvironmentEventDTO();
    failed.setTime(Instant.parse("2026-09-21T06:05:00Z"));
    failed.setLevel("ERROR");
    failed.setType("SKILL_SYNC_FAILED");
    failed.setMessage("skill package sync failed: COMMIT_NOT_FOUND");
    when(environmentService.listEvents(eq(EnvironmentId.of(ENV_ID))))
        .thenReturn(List.of(connecting, failed));

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].type").value("CONNECTING"))
        .andExpect(jsonPath("$.data[0].level").value("INFO"))
        .andExpect(jsonPath("$.data[1].type").value("SKILL_SYNC_FAILED"))
        .andExpect(jsonPath("$.data[1].level").value("ERROR"));
  }

  /** 意图：未知 Environment 的事件查询与详情查询一样映射为 404 资源不存在。 */
  @Test
  void listEnvironmentEventsReturnsNotFoundForMissingEnvironment() throws Exception {
    when(environmentService.listEvents(eq(EnvironmentId.of(ENV_ID))))
        .thenThrow(new AiResourceNotFoundException("environment"));

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/events"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("resource_not_found"));
  }

  /**
   * 意图：验证 GET /api/harness/environments/{id}/token 是幂等只读端点，返回当前 token 与版本，并通过 Cache-Control:
   * no-store 明确禁止任何缓存；它不得调用 rotateToken。
   */
  @Test
  void getRegistrationTokenReturnsCurrentTokenWithNoStore() throws Exception {
    EnvironmentRegistrationTokenDTO token = new EnvironmentRegistrationTokenDTO();
    token.setId(ENV_ID.toString());
    token.setRegistrationToken("current-token");
    token.setVersion("4");
    when(environmentService.getRegistrationToken(eq(EnvironmentId.of(ENV_ID)))).thenReturn(token);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/token"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").value("current-token"))
        .andExpect(jsonPath("$.data.version").value("4"));

    verify(environmentService).getRegistrationToken(EnvironmentId.of(ENV_ID));
    verify(environmentService, never()).rotateToken(any(), any());
  }

  /**
   * 意图：验证 GET /api/harness/environments/{id} 普通详情只返回 Card 投影，绝不返回 registrationToken（凭据不进入列表/详情
   * 缓存路径）。
   */
  @Test
  void getEnvironmentNeverReturnsRegistrationToken() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").doesNotExist());
  }

  /**
   * 意图：验证 POST /api/harness/environments/{environmentId}/registration-token 从请求体读取
   * {expectedVersion} 并轮换 token，返回 200 且禁止缓存。
   */
  @Test
  void rotateTokenReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setRegistrationToken("new-secret-token");
    when(environmentService.rotateToken(eq(EnvironmentId.of(ENV_ID)), eq("0"))).thenReturn(card);

    mockMvc
        .perform(
            post("/api/harness/environments/" + ENV_ID + "/registration-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"0\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.registrationToken").value("new-secret-token"));

    verify(environmentService).rotateToken(EnvironmentId.of(ENV_ID), "0");
  }

  /** 意图：Environment name 是不可变身份，改名端点已彻底移除；只有 token 轮换可以推进 version。 */
  @Test
  void environmentUpdateEndpointIsGone() throws Exception {
    mockMvc
        .perform(
            put("/api/harness/environments/" + ENV_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"renamed\",\"expectedVersion\":\"0\"}"))
        .andExpect(status().isMethodNotAllowed());
  }

  /**
   * 意图：验证 DELETE /api/harness/environments/{environmentId} 依然通过 query param 传递 expectedVersion，并返回
   * HTTP 204 No Content。
   */
  @Test
  void deleteEnvironmentReturnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/harness/environments/" + ENV_ID).param("expectedVersion", "0"))
        .andExpect(status().isNoContent());

    verify(environmentService).delete(EnvironmentId.of(ENV_ID), "0");
  }

  /** 意图：Card 直接暴露连接行保留的最近一次 READY 宿主 metadata；没有独立 runtime 端点，也没有报告历史。 */
  @Test
  void getEnvironmentExposesRetainedHostMetadata() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    card.setOperatingSystem("linux");
    card.setTimeZone("UTC");
    card.setNote("Linux environment.");
    card.setUserName("dev");
    card.setHomeDirectory("/home/dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.operatingSystem").value("linux"))
        .andExpect(jsonPath("$.data.timeZone").value("UTC"))
        .andExpect(jsonPath("$.data.note").value("Linux environment."))
        .andExpect(jsonPath("$.data.userName").value("dev"))
        .andExpect(jsonPath("$.data.homeDirectory").value("/home/dev"));
  }

  /** 意图：从未 READY 的 Environment 的宿主 metadata 字段为 null，而不是伪造默认值。 */
  @Test
  void getEnvironmentOmitsHostMetadataWhenNeverReady() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.operatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.timeZone").doesNotExist())
        .andExpect(jsonPath("$.data.note").doesNotExist())
        .andExpect(jsonPath("$.data.rootPath").doesNotExist());
  }

  /** 意图：runtime 端点已随持久 runtime 报告一起移除，访问必须 404。 */
  @Test
  void runtimeEndpointIsGone() throws Exception {
    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isNotFound());
  }

  /** 意图：Environment 异步管理操作端点已随 environment_operation 表一起移除，访问必须 404。 */
  @Test
  void operationsEndpointsAreGone() throws Exception {
    mockMvc
        .perform(get("/api/harness/environments/" + ENV_ID + "/operations"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get(
                "/api/harness/environments/"
                    + ENV_ID
                    + "/operations/33333333-3333-3333-3333-333333333333"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post(
                "/api/harness/environments/"
                    + ENV_ID
                    + "/operations/33333333-3333-3333-3333-333333333333/cancel"))
        .andExpect(status().isNotFound());
  }
}
