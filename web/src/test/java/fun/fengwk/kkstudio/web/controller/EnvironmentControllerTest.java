package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventLevel;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventType;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallConfigUpdateDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.http.EnvironmentController;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.web.HttpBoundaryConfiguration;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Environment 资源 HTTP 契约：路由、状态码、凭据响应禁缓存与脚本原文。 */
class EnvironmentControllerTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private EnvironmentService environmentService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    environmentService = mock(EnvironmentService.class);
    // 复用生产 HTTP 边界：同一严格 mapper、同一 String→Long/UUID/操作系统转换器与绑定 advice。
    HttpBoundaryConfiguration boundary =
        new HttpBoundaryConfiguration(StrictHttpJsonMapperTestSupport.get());
    FormattingConversionService conversion = new FormattingConversionService();
    boundary.addFormatters(conversion);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new EnvironmentController(environmentService))
            .setConversionService(conversion)
            .setMessageConverters(
                new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(
                new HttpBoundaryConfiguration.CanonicalUuidBinding(),
                new ProblemAdvice(new MessageService()))
            .build();
  }

  /** 意图：验证 GET /api/platform/environments 列表查询返回 200 与环境数组。 */
  @Test
  void listEnvironmentsReturnsOk() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setId(ENV_ID.toString());
    environment.setName("dev");
    environment.setStatus(EnvironmentStatus.READY);
    when(environmentService.list()).thenReturn(List.of(environment));

    mockMvc
        .perform(get("/api/platform/environments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$[0].name").value("dev"))
        .andExpect(jsonPath("$[0].status").value("READY"));
  }

  /** 意图：验证 GET /api/platform/environments/{environmentId} 查询已存在的环境返回 200 与环境信息。 */
  @Test
  void getEnvironmentReturnsOk() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setId(ENV_ID.toString());
    environment.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(environment);

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.name").value("dev"));
  }

  /** 意图：缺失 Environment 映射为 404 not_found，而不是泄露为 500。 */
  @Test
  void getMissingEnvironmentReturnsNotFound() throws Exception {
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID))))
        .thenThrow(DomainException.notFound("environment"));

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.detail").value("environment not found"));
  }

  /** 意图：验证 POST /api/platform/environments 创建环境成功返回 201，且携带凭据的响应禁止缓存。 */
  @Test
  void createEnvironmentReturnsCreated() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setId(ENV_ID.toString());
    environment.setName("new-env");
    environment.setRegistrationToken("secret-token");
    when(environmentService.create(any(EnvironmentCreateDTO.class))).thenReturn(environment);

    mockMvc
        .perform(
            post("/api/platform/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-env\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.registrationToken").value("secret-token"));
  }

  /** 意图：验证 GET .../events 按时间正序返回事件窗口，级别与类型是封闭枚举。 */
  @Test
  void listEnvironmentEventsReturnsChronologicalWindow() throws Exception {
    EnvironmentEventDTO connecting = new EnvironmentEventDTO();
    connecting.setOccurredAt(Instant.parse("2026-09-21T06:00:00Z"));
    connecting.setLevel(EnvironmentEventLevel.INFO);
    connecting.setType(EnvironmentEventType.CONNECTING);
    connecting.setMessage("daemon connection accepted");
    EnvironmentEventDTO failed = new EnvironmentEventDTO();
    failed.setOccurredAt(Instant.parse("2026-09-21T06:05:00Z"));
    failed.setLevel(EnvironmentEventLevel.ERROR);
    failed.setType(EnvironmentEventType.SKILL_SYNC_FAILED);
    failed.setMessage("skill package sync failed: COMMIT_NOT_FOUND");
    when(environmentService.listEvents(eq(EnvironmentId.of(ENV_ID))))
        .thenReturn(List.of(connecting, failed));

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID + "/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].type").value("CONNECTING"))
        .andExpect(jsonPath("$[0].level").value("INFO"))
        .andExpect(jsonPath("$[0].occurredAt").exists())
        .andExpect(jsonPath("$[1].type").value("SKILL_SYNC_FAILED"))
        .andExpect(jsonPath("$[1].level").value("ERROR"));
  }

  /** 意图：未知 Environment 的事件查询与详情查询一样映射为 404 资源不存在。 */
  @Test
  void listEnvironmentEventsReturnsNotFoundForMissingEnvironment() throws Exception {
    when(environmentService.listEvents(eq(EnvironmentId.of(ENV_ID))))
        .thenThrow(DomainException.notFound("environment"));

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID + "/events"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
  }

  /** 意图：普通详情只返回 Environment 投影，绝不返回 registrationToken（凭据不进入列表/详情缓存路径）。 */
  @Test
  void getEnvironmentNeverReturnsRegistrationToken() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setId(ENV_ID.toString());
    environment.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(environment);

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.registrationToken").doesNotExist());
  }

  /** 意图：验证 POST .../rotate-token 从请求体读取 {expectedVersion} 并轮换 token，返回 200 且禁止缓存。 */
  @Test
  void rotateTokenReturnsOk() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setId(ENV_ID.toString());
    environment.setRegistrationToken("new-secret-token");
    when(environmentService.rotateToken(eq(EnvironmentId.of(ENV_ID)), eq(0L)))
        .thenReturn(environment);

    mockMvc
        .perform(
            post("/api/platform/environments/" + ENV_ID + "/rotate-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"0\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.registrationToken").value("new-secret-token"));

    verify(environmentService).rotateToken(EnvironmentId.of(ENV_ID), 0L);
  }

  /** 意图：验证 DELETE /api/platform/environments/{id} 通过 query param 传递 expectedVersion 并返回 204。 */
  @Test
  void deleteEnvironmentReturnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/platform/environments/" + ENV_ID).param("expectedVersion", "0"))
        .andExpect(status().isNoContent());

    verify(environmentService).delete(EnvironmentId.of(ENV_ID), 0L);
  }

  /** 非规范 query expectedVersion 必须在真实严格转换器处拒绝，不进入领域服务。 */
  @Test
  void rejectsNonCanonicalExpectedVersionInQuery() throws Exception {
    mockMvc
        .perform(delete("/api/platform/environments/" + ENV_ID).param("expectedVersion", " 0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    verifyNoInteractions(environmentService);
  }

  /** 非规范请求体 expectedVersion 必须在真实严格 mapper 处拒绝，不进入领域服务。 */
  @Test
  void rejectsNonCanonicalExpectedVersionInBody() throws Exception {
    mockMvc
        .perform(
            put("/api/platform/environments/" + ENV_ID + "/install-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":\" 0\",\"installConfig\":{\"operatingSystem\":\"linux\","
                        + "\"daemon\":{\"studioUrl\":\"https://studio.example.com\"}}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
    verifyNoInteractions(environmentService);
  }

  /** 意图：Environment 直接暴露连接行保留的最近一次 READY 宿主 metadata，操作系统按 wire 值输出。 */
  @Test
  void getEnvironmentExposesRetainedHostMetadata() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setId(ENV_ID.toString());
    environment.setName("dev");
    environment.setOperatingSystem(DaemonOperatingSystem.LINUX);
    environment.setTimeZone("UTC");
    environment.setNote("Linux environment.");
    environment.setUserName("dev");
    environment.setHomeDirectory("/home/dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(environment);

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operatingSystem").value("linux"))
        .andExpect(jsonPath("$.timeZone").value("UTC"))
        .andExpect(jsonPath("$.note").value("Linux environment."))
        .andExpect(jsonPath("$.userName").value("dev"))
        .andExpect(jsonPath("$.homeDirectory").value("/home/dev"));
  }

  /** 意图：从未 READY 的 Environment 的宿主 metadata 字段为 null，而不是伪造默认值。 */
  @Test
  void getEnvironmentOmitsHostMetadataWhenNeverReady() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setId(ENV_ID.toString());
    environment.setName("dev");
    when(environmentService.get(eq(EnvironmentId.of(ENV_ID)))).thenReturn(environment);

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operatingSystem").doesNotExist())
        .andExpect(jsonPath("$.timeZone").doesNotExist())
        .andExpect(jsonPath("$.note").doesNotExist());
  }

  /** 签发响应保留过期时间，不返回长期凭据，并禁止缓存。 */
  @Test
  void installCodeResponseUsesNoStoreAndExpiry() throws Exception {
    EnvironmentInstallCodeDTO issued = new EnvironmentInstallCodeDTO();
    issued.setCode("short-code");
    issued.setExpiresAt(Instant.parse("2026-10-05T00:05:00Z"));
    when(environmentService.issueInstallCode(EnvironmentId.of(ENV_ID), 4L)).thenReturn(issued);

    mockMvc
        .perform(
            post("/api/platform/environments/" + ENV_ID + "/install-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"4\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.code").value("short-code"))
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.registrationToken").doesNotExist());
    verify(environmentService).issueInstallCode(EnvironmentId.of(ENV_ID), 4L);
  }

  /** 错误 code 必须保持 HTTP 400，不能按成功脚本返回。 */
  @Test
  void invalidInstallCodeReturnsValidationError() throws Exception {
    when(environmentService.installationScript(EnvironmentId.of(ENV_ID), "expired-code"))
        .thenThrow(
            DomainException.validation("code", "install code is missing, expired or invalid"));

    mockMvc
        .perform(
            get("/api/platform/environments/" + ENV_ID + "/install-script")
                .param("code", "expired-code"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("install code is missing, expired or invalid"))
        .andExpect(content().string(not(containsString("expired-code"))));
  }

  /** 意图：安装与卸载脚本以纯文本返回，带禁止缓存和禁止嗅探头，且不被 JSON 包装。 */
  @Test
  void installAndUninstallScriptsUsePlainTextHeaders() throws Exception {
    when(environmentService.installationScript(eq(EnvironmentId.of(ENV_ID)), eq("valid-code")))
        .thenReturn("echo 汉字 ' $ `");
    when(environmentService.uninstallationScript(DaemonOperatingSystem.WINDOWS))
        .thenReturn("uninstall windows");

    mockMvc
        .perform(
            get("/api/platform/environments/" + ENV_ID + "/install-script")
                .param("code", "valid-code"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(content().string("echo 汉字 ' $ `"))
        .andExpect(content().string(not(containsString("\"data\""))));
    mockMvc
        .perform(get("/api/platform/daemon-uninstall-scripts/windows"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
        .andExpect(content().string("uninstall windows"));
  }

  /** 意图：未知身份、非法身份与非法操作系统分别返回 404/400，且错误正文不回显凭据。 */
  @Test
  void installAndUninstallFailuresDoNotEchoCredentials() throws Exception {
    when(environmentService.installationScript(eq(EnvironmentId.of(ENV_ID)), eq("bad-code")))
        .thenThrow(DomainException.notFound("environment"));

    mockMvc
        .perform(
            get("/api/platform/environments/" + ENV_ID + "/install-script")
                .param("code", "bad-code"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(not(containsString(ENV_ID.toString()))));
    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID + "/install-script"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/platform/environments/not-a-uuid/install-script").param("code", "x"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/platform/daemon-uninstall-scripts/beos"))
        .andExpect(status().isBadRequest());
  }

  /** 意图：PUT .../install-settings 用类型化 DTO 整体替换安装设置，并返回更新后的 Environment。 */
  @Test
  void updateInstallSettingsAcceptsTypedBody() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setId(ENV_ID.toString());
    when(environmentService.updateInstallConfig(
            eq(EnvironmentId.of(ENV_ID)), any(EnvironmentInstallConfigUpdateDTO.class)))
        .thenReturn(environment);

    mockMvc
        .perform(
            put("/api/platform/environments/" + ENV_ID + "/install-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\","
                        + "\"daemon\":{\"studioUrl\":\"https://studio.example.com\"}}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ENV_ID.toString()));

    verify(environmentService)
        .updateInstallConfig(
            eq(EnvironmentId.of(ENV_ID)), any(EnvironmentInstallConfigUpdateDTO.class));
  }
}
