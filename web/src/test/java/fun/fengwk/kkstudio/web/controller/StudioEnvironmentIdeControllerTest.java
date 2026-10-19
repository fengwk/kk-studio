package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.platform.environment.ide.IdeOriginProperties;
import fun.fengwk.kkstudio.platform.environment.ide.InMemoryIdeBootstrapTicketService;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.web.advice.StudioDomainErrorAdvice;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Environment IDE 视图控制 API 的实际 HTTP 契约：成功路径映射与参数转发，以及 domain 错误（dirty 拒绝 400、IDE 通道不可用
 * 409）和请求体错误的统一 {@code Result} 信封。
 */
class StudioEnvironmentIdeControllerTest {

  private static final UUID ENV_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String VIEW_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String DIRTY_REJECTION =
      "save or discard unsaved changes before switching directory";
  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

  private EnvironmentIdeService environmentIdeService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    environmentIdeService = mock(EnvironmentIdeService.class);
    StudioEnvironmentIdeController controller =
        new StudioEnvironmentIdeController(environmentIdeService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioDomainErrorAdvice(new StudioMessageService()),
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  /** 意图：OPEN_VIEW 返回 200 与受控 entryUrl，并把路径中的 environmentId 与请求体原样转发给服务。 */
  @Test
  void openViewForwardsRequestAndReturnsEntryUrl() throws Exception {
    EnvironmentIdeViewDTO view = view("/workspace");
    view.setEntryUrl("http://" + ENV_ID + ".ide.browser.localhost:8080/ide/bootstrap?ticket=t");
    when(environmentIdeService.openView(eq(EnvironmentId.of(ENV_ID)), any())).thenReturn(view);

    mockMvc
        .perform(
            post("/api/environments/" + ENV_ID + "/ide/views")
                .locale(Locale.US)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"viewId\":\"" + VIEW_ID + "\",\"directory\":\"/workspace\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.viewId").value(VIEW_ID))
        .andExpect(jsonPath("$.data.environmentId").value(ENV_ID.toString()))
        .andExpect(jsonPath("$.data.directory").value("/workspace"))
        .andExpect(
            jsonPath("$.data.entryUrl")
                .value("http://" + ENV_ID + ".ide.browser.localhost:8080/ide/bootstrap?ticket=t"));

    ArgumentCaptor<EnvironmentIdeOpenViewDTO> request =
        ArgumentCaptor.forClass(EnvironmentIdeOpenViewDTO.class);
    verify(environmentIdeService).openView(eq(EnvironmentId.of(ENV_ID)), request.capture());
    assertEquals(VIEW_ID, request.getValue().getViewId());
    assertEquals("/workspace", request.getValue().getDirectory());
  }

  /**
   * 真实浏览器验收的回归点：Daemon 拒绝 dirty 文档换目录必须保持 HTTP 400 与 {@code validation} code，且 Daemon 文案进入 {@code
   * errors.detail}（浏览器据此确认拒绝原因就是未保存改动，而不是其它校验失败）。
   */
  @Test
  void changeDirectoryKeepsDirtyRejectionAsBadRequestValidation() throws Exception {
    when(environmentIdeService.changeDirectory(eq(EnvironmentId.of(ENV_ID)), eq(VIEW_ID), any()))
        .thenThrow(new AiValidationException("environment", DIRTY_REJECTION));

    mockMvc
        .perform(
            put("/api/environments/" + ENV_ID + "/ide/views/" + VIEW_ID + "/directory")
                .locale(Locale.US)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"directory\":\"/workspace/subdir\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail").value(DIRTY_REJECTION))
        .andExpect(jsonPath("$.message").value("Invalid environment request."));
  }

  /** 意图：CHANGE_DIRECTORY 成功时返回 200 与更新后的目录，不伪造新的 bootstrap 入口。 */
  @Test
  void changeDirectoryReturnsUpdatedDirectory() throws Exception {
    when(environmentIdeService.changeDirectory(eq(EnvironmentId.of(ENV_ID)), eq(VIEW_ID), any()))
        .thenReturn(view("/workspace/subdir"));

    mockMvc
        .perform(
            put("/api/environments/" + ENV_ID + "/ide/views/" + VIEW_ID + "/directory")
                .locale(Locale.US)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"directory\":\"/workspace/subdir\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.viewId").value(VIEW_ID))
        .andExpect(jsonPath("$.data.directory").value("/workspace/subdir"))
        .andExpect(jsonPath("$.data.entryUrl").doesNotExist());

    ArgumentCaptor<EnvironmentIdeChangeDirectoryDTO> request =
        ArgumentCaptor.forClass(EnvironmentIdeChangeDirectoryDTO.class);
    verify(environmentIdeService)
        .changeDirectory(eq(EnvironmentId.of(ENV_ID)), eq(VIEW_ID), request.capture());
    assertEquals("/workspace/subdir", request.getValue().getDirectory());
  }

  /**
   * 意图：离线/排队/超时等 IDE 通道故障统一映射为 409 与稳定 {@code ide_unavailable} code，机器码与 resource 不随 locale 变化，用户可见
   * message 按请求 locale 本地化。
   */
  @ParameterizedTest
  @CsvSource({
    "en-US,The environment IDE is unavailable. Try again later.",
    "zh-CN,环境 IDE 当前不可用，请稍后重试。"
  })
  void ideChannelFailuresReturnTypedConflictInRequestLocale(
      String languageTag, String expectedMessage) throws Exception {
    when(environmentIdeService.changeDirectory(eq(EnvironmentId.of(ENV_ID)), eq(VIEW_ID), any()))
        .thenThrow(new AiEnvironmentIdeUnavailableException("environment IDE command timed out"));

    mockMvc
        .perform(
            put("/api/environments/" + ENV_ID + "/ide/views/" + VIEW_ID + "/directory")
                .locale(Locale.forLanguageTag(languageTag))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"directory\":\"/workspace/subdir\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("ide_unavailable"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail").value("environment IDE command timed out"))
        .andExpect(jsonPath("$.message").value(expectedMessage));
  }

  /** 意图：非法 environmentId 在进入服务之前即被拒绝为 400，且错误 resource 指向该参数。 */
  @Test
  void invalidEnvironmentIdIsRejectedWithoutTouchingService() throws Exception {
    mockMvc
        .perform(
            post("/api/environments/not-a-uuid/ide/views")
                .locale(Locale.US)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"viewId\":\"" + VIEW_ID + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("environmentId"));

    verifyNoInteractions(environmentIdeService);
  }

  /** 意图：无法反序列化的请求体仍走项目 400 Result 信封，不泄露 parser 细节也不触达服务。 */
  @Test
  void malformedJsonBodyUsesCanonicalBadRequestEnvelope() throws Exception {
    mockMvc
        .perform(
            put("/api/environments/" + ENV_ID + "/ide/views/" + VIEW_ID + "/directory")
                .locale(Locale.US)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ invalid json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.errors.type").value("about:blank"))
        .andExpect(jsonPath("$.errors.title").value("Bad Request"))
        .andExpect(jsonPath("$.errors.detail").value("Failed to read request"));

    verifyNoInteractions(environmentIdeService);
  }

  /** 意图：FOCUS_TERMINAL 语义为无响应体的 204，CLOSE_VIEW 幂等清理并同样返回 204，两者都只转发 viewId。 */
  @Test
  void focusTerminalAndCloseViewReturnNoContent() throws Exception {
    mockMvc
        .perform(
            post("/api/environments/" + ENV_ID + "/ide/views/" + VIEW_ID + "/terminal")
                .locale(Locale.US))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            delete("/api/environments/" + ENV_ID + "/ide/views/" + VIEW_ID)
                .locale(Locale.US)
                .content(""))
        .andExpect(status().isNoContent());

    verify(environmentIdeService).focusTerminal(EnvironmentId.of(ENV_ID), VIEW_ID);
    verify(environmentIdeService).closeView(EnvironmentId.of(ENV_ID), VIEW_ID);
  }

  /** 意图：未知字段必须被严格拒绝，避免 IDE 契约静默漂移。 */
  @Test
  void rejectsUnknownRequestFields() throws Exception {
    mockMvc
        .perform(
            put("/api/environments/" + ENV_ID + "/ide/views/" + VIEW_ID + "/directory")
                .locale(Locale.US)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"directory\":\"/workspace\",\"unknown\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.errors.detail").value(containsString("Failed to read request")));

    verifyNoInteractions(environmentIdeService);
  }

  /**
   * 意图：坏目录（空白/超长/控制字符）在真实服务链路与统一 Advice 组合下必须是 400 {@code validation}，而不是穿透为 500；OPEN_VIEW 与
   * CHANGE_DIRECTORY 共用同一 wire 事实源，且失败不产生任何 Daemon 命令（无 Native 视图副作用）。
   */
  @ParameterizedTest
  @MethodSource("invalidDirectories")
  void invalidDirectoryIsRejectedAsBadRequestWithoutDaemonCommand(String directory)
      throws Exception {
    RecordingIdeTransport transport = new RecordingIdeTransport();
    IdeOriginProperties originProperties = new IdeOriginProperties();
    originProperties.setOriginTemplate("http://{environmentId}.ide.test.localhost");
    EnvironmentIdeService service =
        new EnvironmentIdeServiceImpl(
            transport,
            originProperties,
            new InMemoryIdeBootstrapTicketService(),
            Duration.ofSeconds(5));
    MockMvc realChain =
        MockMvcBuilders.standaloneSetup(new StudioEnvironmentIdeController(service))
            .setControllerAdvice(
                new StudioDomainErrorAdvice(new StudioMessageService()),
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();

    EnvironmentIdeOpenViewDTO openRequest = new EnvironmentIdeOpenViewDTO();
    openRequest.setViewId(VIEW_ID);
    openRequest.setDirectory(directory);
    realChain
        .perform(
            post("/api/environments/" + ENV_ID + "/ide/views")
                .locale(Locale.US)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OBJECT_MAPPER.writeValueAsString(openRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail", containsString("directory")));

    EnvironmentIdeChangeDirectoryDTO changeRequest = new EnvironmentIdeChangeDirectoryDTO();
    changeRequest.setDirectory(directory);
    realChain
        .perform(
            put("/api/environments/" + ENV_ID + "/ide/views/" + VIEW_ID + "/directory")
                .locale(Locale.US)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OBJECT_MAPPER.writeValueAsString(changeRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("environment"))
        .andExpect(jsonPath("$.errors.detail", containsString("directory")));

    assertNull(transport.command);
  }

  private static Stream<String> invalidDirectories() {
    return Stream.of("   ", "a".repeat(4097), "/tmp/\u0001");
  }

  private static EnvironmentIdeViewDTO view(String directory) {
    EnvironmentIdeViewDTO view = new EnvironmentIdeViewDTO();
    view.setViewId(VIEW_ID);
    view.setEnvironmentId(ENV_ID.toString());
    view.setDirectory(directory);
    return view;
  }

  /** 记录发出的 IDE 命令的传输替身：真实服务链路只需证明坏目录未产生任何命令。 */
  private static final class RecordingIdeTransport implements IdeCommandTransport {

    private static final String DAEMON_INSTANCE = "33333333-3333-3333-3333-333333333333";
    private static final UUID LEASE = new UUID(0L, 77L);

    IdeCommand command;

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand ideCommand) {
      command = ideCommand;
      CompletableFuture<IdeReply> reply = new CompletableFuture<>();
      reply.complete(
          IdeReply.ok(
              ideCommand.viewId(),
              ideCommand.directory(),
              "/daemon/workspaces/" + ideCommand.viewId()));
      return new IdeRequest(
          new IdeSessionIdentity(environmentId, DAEMON_INSTANCE, LEASE), UUID.randomUUID(), reply);
    }

    @Override
    public void abandonIde(UUID id) {
      // 这里的替身不需要收敛语义：合法命令会立即成功回执。
    }

    @Override
    public Optional<IdeSessionIdentity> ideSessionIdentity(EnvironmentId environmentId) {
      return Optional.empty();
    }
  }
}
