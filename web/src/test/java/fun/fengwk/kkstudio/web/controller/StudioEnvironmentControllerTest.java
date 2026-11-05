package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.platform.error.DomainException;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.share.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.environment.EnvironmentEventDTO;
import fun.fengwk.kkstudio.share.environment.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.share.environment.EnvironmentRuntimeDTO;
import fun.fengwk.kkstudio.share.environment.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.share.environment.EnvironmentUpdateProgressDTO;
import fun.fengwk.kkstudio.share.environment.EnvironmentUpdateStartDTO;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;
import fun.fengwk.kkstudio.web.advice.StudioDomainErrorAdvice;
import fun.fengwk.kkstudio.web.environment.EnvironmentRuntimeRoutingService;
import fun.fengwk.kkstudio.web.environment.cluster.EnvironmentRuntimeOwnerClient;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class StudioEnvironmentControllerTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private EnvironmentService environmentService;
  private EnvironmentUpdateService environmentUpdateService;
  private ClusterNodeRegistry clusterNodeRegistry;
  private EnvironmentRuntimeOwnerClient runtimeOwnerClient;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    environmentService = mock(EnvironmentService.class);
    environmentUpdateService = mock(EnvironmentUpdateService.class);
    clusterNodeRegistry = mock(ClusterNodeRegistry.class);
    runtimeOwnerClient = mock(EnvironmentRuntimeOwnerClient.class);
    when(clusterNodeRegistry.route(any()))
        .thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    StudioEnvironmentController controller =
        new StudioEnvironmentController(
            environmentService,
            new EnvironmentRuntimeRoutingService(
                environmentUpdateService, clusterNodeRegistry, runtimeOwnerClient));
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioDomainErrorAdvice(new StudioMessageService()),
                new ApiResponseStatusAdvice())
            .build();
  }

  /** 意图：验证 GET /api/environments 列表查询返回 200 与卡片列表。 */
  @Test
  void listEnvironmentsReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    card.setStatus("READY");
    when(environmentService.list()).thenReturn(List.of(card));

    mockMvc
        .perform(get("/api/environments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data[0].name").value("dev"));
  }

  /** 意图：验证 GET /api/environments/{environmentId} 查询已存在的环境返回 200 与对应的卡片信息。 */
  @Test
  void getEnvironmentReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.name").value("dev"));
  }

  /**
   * 意图：验证当服务层抛出 DomainException 时，由统一 StudioDomainErrorAdvice 映射为 HTTP 404 与 RESOURCE_NOT_FOUND
   * (code="resource_not_found") 错误信封，而不是泄露为 500。
   */
  @Test
  void getMissingEnvironmentReturnsNotFound() throws Exception {
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID))))
        .thenThrow(DomainException.notFound("environment"));

    mockMvc
        .perform(get("/api/environments/" + ENV_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("resource_not_found"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail").value("environment not found"));
  }

  /** 意图：验证 POST /api/environments 创建环境成功返回 HTTP 201 Created，且携带凭据的响应禁止缓存。 */
  @Test
  void createEnvironmentReturnsCreated() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("new-env");
    card.setRegistrationToken("secret-token");
    when(environmentService.create(any(EnvironmentCreateDTO.class))).thenReturn(card);

    mockMvc
        .perform(
            post("/api/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-env\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").value("secret-token"));
  }

  /** 意图：验证 GET /api/environments/{id}/events 按时间正序返回事件窗口。 */
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
        .perform(get("/api/environments/" + ENV_ID + "/events"))
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
        .thenThrow(DomainException.notFound("environment"));

    mockMvc
        .perform(get("/api/environments/" + ENV_ID + "/events"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("resource_not_found"));
  }

  /** 意图：验证 GET /api/environments/{id} 普通详情只返回 Card 投影，绝不返回 registrationToken（凭据不进入列表/详情 缓存路径）。 */
  @Test
  void getEnvironmentNeverReturnsRegistrationToken() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(card);

    mockMvc
        .perform(get("/api/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.registrationToken").doesNotExist());
  }

  /**
   * 意图：验证 POST /api/environments/{environmentId}/registration-token 从请求体读取 {expectedVersion} 并轮换
   * token，返回 200 且禁止缓存。
   */
  @Test
  void rotateTokenReturnsOk() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setId(ENV_ID.toString());
    card.setRegistrationToken("new-secret-token");
    when(environmentService.rotateToken(eq(EnvironmentId.of(ENV_ID)), eq("0"))).thenReturn(card);

    mockMvc
        .perform(
            post("/api/environments/" + ENV_ID + "/registration-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"0\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.registrationToken").value("new-secret-token"));

    verify(environmentService).rotateToken(EnvironmentId.of(ENV_ID), "0");
  }

  /**
   * 意图：验证 DELETE /api/environments/{environmentId} 依然通过 query param 传递 expectedVersion，并返回 HTTP 204
   * No Content。
   */
  @Test
  void deleteEnvironmentReturnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/environments/" + ENV_ID).param("expectedVersion", "0"))
        .andExpect(status().isNoContent());

    verify(environmentService).delete(EnvironmentId.of(ENV_ID), "0");
  }

  /** 意图：Card 直接暴露连接行保留的最近一次 READY 宿主 metadata。 */
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
        .perform(get("/api/environments/" + ENV_ID))
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
        .perform(get("/api/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.operatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.timeZone").doesNotExist())
        .andExpect(jsonPath("$.data.note").doesNotExist());
  }

  /** 签发响应保留 JSON 信封和 ISO 过期时间，不返回长期凭据。 */
  @Test
  void installCodeResponseUsesNoStoreAndIsoExpiry() throws Exception {
    EnvironmentInstallCodeDTO issued = new EnvironmentInstallCodeDTO();
    issued.setCode("short-code");
    issued.setExpiresAt(Instant.parse("2026-10-05T00:05:00Z"));
    when(environmentService.issueInstallCode(EnvironmentId.of(ENV_ID), "4")).thenReturn(issued);

    mockMvc
        .perform(
            post("/api/environments/" + ENV_ID + "/install-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"4\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.code").value("short-code"))
        .andExpect(jsonPath("$.data.expiresAt").value("2026-10-05T00:05:00Z"))
        .andExpect(jsonPath("$.data.registrationToken").doesNotExist());
    verify(environmentService).issueInstallCode(EnvironmentId.of(ENV_ID), "4");
  }

  /** 错误 code 必须保持 HTTP 400，不能按成功脚本返回。 */
  @Test
  void invalidInstallCodeReturnsValidationError() throws Exception {
    when(environmentService.installationScript(EnvironmentId.of(ENV_ID), "expired-code"))
        .thenThrow(
            DomainException.validation("code", "install code is missing, expired or invalid"));

    mockMvc
        .perform(get("/api/environments/" + ENV_ID + "/install").param("code", "expired-code"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.resource").value("code"))
        .andExpect(content().string(not(containsString("expired-code"))));
  }

  /** 意图：安装与卸载脚本以纯文本返回，带禁止缓存和禁止嗅探头，且不被 Result JSON 包装。 */
  @Test
  void installAndUninstallScriptsUsePlainTextHeaders() throws Exception {
    when(environmentService.installationScript(eq(EnvironmentId.of(ENV_ID)), eq("valid-code")))
        .thenReturn("echo 汉字 ' $ `");
    when(environmentService.uninstallationScript("windows")).thenReturn("uninstall windows");

    mockMvc
        .perform(get("/api/environments/" + ENV_ID + "/install").param("code", "valid-code"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(content().string("echo 汉字 ' $ `"))
        .andExpect(content().string(not(containsString("\"data\""))));
    mockMvc
        .perform(get("/api/environments/uninstall/windows"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
        .andExpect(content().string("uninstall windows"));
  }

  /** 意图：未知身份、非法身份与非法操作系统分别返回 404/400，且错误正文不回显凭据。 */
  @Test
  void installAndUninstallFailuresDoNotEchoCredentials() throws Exception {
    when(environmentService.installationScript(eq(EnvironmentId.of(ENV_ID)), eq("bad-code")))
        .thenThrow(DomainException.notFound("environment"));
    when(environmentService.uninstallationScript("bad"))
        .thenThrow(
            DomainException.validation(
                "operatingSystem", "operatingSystem must be linux, macos or windows"));

    mockMvc
        .perform(get("/api/environments/" + ENV_ID + "/install").param("code", "bad-code"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(not(containsString(ENV_ID.toString()))));
    mockMvc
        .perform(get("/api/environments/" + ENV_ID + "/install"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/environments/not-a-uuid/install").param("code", "x"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.resource").value("environmentId"));
    mockMvc
        .perform(get("/api/environments/uninstall/bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.resource").value("operatingSystem"));
  }

  /** 意图：local owner 直接返回内存 runtime 快照，revision/status 与显式 null update 都按 wire 输出。 */
  @Test
  void runtimeEndpointReturnsCurrentSnapshotFromLocalOwner() throws Exception {
    when(environmentUpdateService.runtimeStatus(EnvironmentId.of(ENV_ID)))
        .thenReturn(runtimeStatus("7", "1.0.9", null));

    mockMvc
        .perform(get("/api/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value("7"))
        .andExpect(jsonPath("$.data.status.daemonVersion").value("1.0.9"))
        .andExpect(jsonPath("$.data.status.update").value(nullValue()));
  }

  /** 意图：没有 READY owner 路由时明确返回 environment_unavailable，不能伪造空 update 快照。 */
  @Test
  void runtimeEndpointReturnsUnavailableWithoutOwnerRoute() throws Exception {
    when(clusterNodeRegistry.route(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("environment_unavailable"));
  }

  /** 意图：非 owner 的 runtime 读取同样只做一次内部 HMAC 调用，不回退本地更新服务。 */
  @Test
  void runtimeEndpointUsesOwnerSingleHop() throws Exception {
    ClusterOwnerRoute remote =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("https://owner.internal"));
    when(clusterNodeRegistry.route(any())).thenReturn(Optional.of(remote));
    when(runtimeOwnerClient.runtimeStatus(remote, EnvironmentId.of(ENV_ID)))
        .thenReturn(runtimeStatus("9", "1.0.9", null));

    mockMvc
        .perform(get("/api/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value("9"));
    verify(environmentUpdateService, never()).runtimeStatus(any());
  }

  /** 意图：start update 必须显式表达命令仅进入发送队列，不得让 UI 把发送前快照误报为已启动。 */
  @Test
  void startUpdateReturnsUnconfirmedCommandStatus() throws Exception {
    EnvironmentUpdateStartDTO started = new EnvironmentUpdateStartDTO();
    started.setCommandStatus(EnvironmentUpdateStartDTO.SENT_UNCONFIRMED);
    started.setRuntimeStatus(runtimeStatus("7", "1.0.9", null));
    when(environmentUpdateService.startUpdate(EnvironmentId.of(ENV_ID))).thenReturn(started);

    mockMvc
        .perform(post("/api/environments/" + ENV_ID + "/update"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.commandStatus").value("SENT_UNCONFIRMED"))
        .andExpect(jsonPath("$.data.runtimeStatus.status.daemonVersion").value("1.0.9"));
  }

  /** 意图：非 owner 节点只做一次 HMAC 内部调用，不在本地执行更新。 */
  @Test
  void updateUsesOwnerSingleHop() throws Exception {
    ClusterOwnerRoute remote =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("https://owner.internal"));
    when(clusterNodeRegistry.route(any())).thenReturn(Optional.of(remote));
    EnvironmentUpdateStartDTO started = new EnvironmentUpdateStartDTO();
    started.setCommandStatus(EnvironmentUpdateStartDTO.SENT_UNCONFIRMED);
    started.setRuntimeStatus(runtimeStatus("8", "1.0.9", null));
    when(runtimeOwnerClient.startUpdate(remote, EnvironmentId.of(ENV_ID))).thenReturn(started);

    mockMvc
        .perform(post("/api/environments/" + ENV_ID + "/update"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.commandStatus").value("SENT_UNCONFIRMED"));
    verify(environmentUpdateService, never()).startUpdate(any());
  }

  private static EnvironmentRuntimeStatusDTO runtimeStatus(
      String revision, String daemonVersion, EnvironmentUpdateProgressDTO update) {
    EnvironmentRuntimeDTO status = new EnvironmentRuntimeDTO();
    status.setDaemonVersion(daemonVersion);
    status.setUpdate(update);
    EnvironmentRuntimeStatusDTO snapshot = new EnvironmentRuntimeStatusDTO();
    snapshot.setRevision(revision);
    snapshot.setStatus(status);
    return snapshot;
  }
}
