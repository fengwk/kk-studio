package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.canvas.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasFunctionService;
import fun.fengwk.kkstudio.canvas.CanvasFunctionUnknownResolution;
import fun.fengwk.kkstudio.canvas.CanvasFunctionVersion;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.infra.dto.CanvasDtoMapper;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Function 目录/run HTTP 契约、状态码与 stateJson 隔离。
 *
 * <p>目录只暴露函数名、说明、args schema 与引用限制：没有 model/provider/prompt 身份，也没有任何后端实现细节（endpoint、workflow、
 * parameters）。UNKNOWN 只能通过 resolve 解除，且必须携带人工核查事实。
 */
class StudioCanvasFunctionControllerTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final UUID CANVAS = new UUID(0L, 1L);
  private static final UUID NODE = new UUID(0L, 2L);
  private static final UUID REQUEST = new UUID(0L, 3L);
  private static final String VERIFICATION = "checked the provider console";

  private MockMvc mockMvc;
  private CanvasFunctionService runtimeService;
  private TestCanvasFunctionCatalogSource catalogSource;

  @BeforeEach
  void setUp() {
    runtimeService = mock(CanvasFunctionService.class);
    install(adapter("fake-image", true, null));
  }

  /** 以给定适配器重建 HTTP 边界；仅用于目录可用性断言。 */
  private void install(CanvasFunctionAdapter adapter) {
    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(List.of(adapter));
    catalogSource = new TestCanvasFunctionCatalogSource(catalog);
    mockMvc =
        standaloneSetup(
                new StudioCanvasFunctionController(
                    catalogSource,
                    runtimeService,
                    new CanvasDtoMapper(mock(StorageBlobManager.class))))
            .setControllerAdvice(new ApiResponseStatusAdvice())
            .setMessageConverters(strictJsonConverter())
            .build();
  }

  /** 测试意图：目录以函数名 + args schema 为身份，并暴露该函数定义版本。 */
  @Test
  void listsFunctionsByNameAndArgsSchema() throws Exception {
    mockMvc
        .perform(get("/api/canvas-functions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("fake-image"))
        .andExpect(jsonPath("$.data[0].description").value("Fake Image"))
        .andExpect(jsonPath("$.data[0].argsSchema.type").value("object"))
        .andExpect(jsonPath("$.data[0].argsSchema.properties.prompt.type").value("string"))
        .andExpect(jsonPath("$.data[0].outputs.length()").value(1))
        .andExpect(jsonPath("$.data[0].outputs[0].kind").value("IMAGE"))
        .andExpect(jsonPath("$.data[0].outputs[0].name").value(nullValue()))
        .andExpect(jsonPath("$.data[0].outputKind").doesNotExist())
        .andExpect(jsonPath("$.data[0].referencePolicy.allowedKinds[0]").value("IMAGE"))
        .andExpect(jsonPath("$.data[0].available").value(true))
        .andExpect(jsonPath("$.data[0].unavailableReason").value(nullValue()))
        .andExpect(jsonPath("$.data[0].version.catalogEpoch").doesNotExist())
        .andExpect(
            jsonPath("$.data[0].version.definitionFingerprint")
                .value(expectedVersion().definitionFingerprint()))
        .andExpect(jsonPath("$.data[0].key").doesNotExist())
        .andExpect(jsonPath("$.data[0].model").doesNotExist())
        .andExpect(jsonPath("$.data[0].parameters").doesNotExist())
        .andExpect(jsonPath("$.data[0].endpoint").doesNotExist())
        .andExpect(jsonPath("$.data[0].workflow").doesNotExist());
    // 目录读取必须是一次租用，并在响应前释放。
    assertEquals(0, catalogSource.openLeases());
  }

  /** 测试意图：expectedVersion 必填且形状严格；非法 fingerprint 或未知字段都在边界处 400，不触达应用服务。 */
  @Test
  void startRequiresWellFormedExpectedVersion() throws Exception {
    for (String body :
        new String[] {
          "{\"requestId\":\"request-1\"}",
          "{\"requestId\":\"request-1\",\"expectedVersion\":null}",
          "{\"requestId\":\"request-1\",\"expectedVersion\":{}}",
          "{\"requestId\":\"request-1\",\"expectedVersion\":{\"definitionFingerprint\":\""
              + "A".repeat(64)
              + "\"}}",
          "{\"requestId\":\"request-1\",\"expectedVersion\":{\"definitionFingerprint\":\"abc\"}}",
          "{\"requestId\":\"request-1\",\"expectedVersion\":{\"catalogEpoch\":\"1\","
              + "\"definitionFingerprint\":\""
              + "a".repeat(64)
              + "\"}}",
        }) {
      mockMvc
          .perform(
              post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verify(runtimeService, never()).start(any(), any(), any(), any());
  }

  /** 测试意图：expectedVersion 的 fingerprint 只能是 JSON 字符串；number/bool/array/null 一律在边界拒绝。 */
  @Test
  void expectedVersionRejectsNonStringFingerprint() throws Exception {
    for (String value : new String[] {"123", "true", "[\"x\"]", "null"}) {
      mockMvc
          .perform(
              post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"requestId\":\"request-1\",\"expectedVersion\":{\"definitionFingerprint\":"
                          + value
                          + "}}"))
          .andExpect(status().isBadRequest());
    }
    verify(runtimeService, never()).start(any(), any(), any(), any());
  }

  /** 测试意图：目录已变化时应用服务返回的 409 原样映射，客户端据此刷新目录而不是静默重试。 */
  @Test
  void mapsCatalogChangedConflictTo409() throws Exception {
    when(runtimeService.start(any(), any(), anyString(), any()))
        .thenThrow(
            new CanvasFunctionRunException(
                CanvasFunctionRunException.Reason.CONFLICT,
                "Canvas Function catalog changed; refresh the directory before starting"
                    + " this function"));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"request-1\",\"expectedVersion\":{"
                        + "\"definitionFingerprint\":\""
                        + expectedVersion().definitionFingerprint()
                        + "\"}}"))
        .andExpect(status().isConflict());
  }

  /** 测试意图：cancel 只携带 requestId；start 的 expectedVersion 不能混进 cancel wire。 */
  @Test
  void cancelBodyStaysRequestIdOnly() throws Exception {
    when(runtimeService.cancel(CANVAS, NODE, "request-1"))
        .thenReturn(run(CanvasFunctionRunStatus.CANCELLED, "CANCELLED"));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"request-1\"}"))
        .andExpect(status().isOk());
    verify(runtimeService).cancel(CANVAS, NODE, "request-1");
    verify(runtimeService, never()).start(any(), any(), any(), any());

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"request-1\",\"expectedVersion\":{"
                        + "\"definitionFingerprint\":\""
                        + expectedVersion().definitionFingerprint()
                        + "\"}}"))
        .andExpect(status().isBadRequest());
  }

  /** 测试意图：已安装但未配置的插件函数仍出现在目录，只以 available=false + 原因报告，绝不假装可执行。 */
  @Test
  void reportsInstalledButUnavailableFunctionsWithReason() throws Exception {
    install(adapter("mini-max", false, "comfy endpoint is not configured"));
    mockMvc
        .perform(get("/api/canvas-functions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("mini-max"))
        .andExpect(jsonPath("$.data[0].available").value(false))
        .andExpect(
            jsonPath("$.data[0].unavailableReason").value("comfy endpoint is not configured"));
  }

  /**
   * 测试意图：验证 Canvas Function 统一使用 function-run 路径，POST 启动返回 202 Accepted，GET 查询与 POST 取消返回 200
   * OK，且字段脱敏。
   */
  @Test
  void startGetCancelExposeOnlyPublicRunFields() throws Exception {
    CanvasFunctionRun run = run(CanvasFunctionRunStatus.READY, "QUEUED");
    CanvasFunctionVersion version = expectedVersion();
    when(runtimeService.start(CANVAS, NODE, "request-1", version)).thenReturn(run);
    when(runtimeService.get(CANVAS, NODE)).thenReturn(run);
    when(runtimeService.cancel(CANVAS, NODE, "request-1"))
        .thenReturn(run(CanvasFunctionRunStatus.CANCELLED, "CANCELLED"));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"request-1\",\"expectedVersion\":{"
                        + "\"definitionFingerprint\":\""
                        + version.definitionFingerprint()
                        + "\"}}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.code").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.status").value("READY"))
        .andExpect(jsonPath("$.data.stage").value("QUEUED"))
        .andExpect(jsonPath("$.data.stateJson").doesNotExist());
    mockMvc
        .perform(get("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.requestId").value(REQUEST.toString()));
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"request-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    verify(runtimeService).start(CANVAS, NODE, "request-1", version);
    verify(runtimeService).cancel(CANVAS, NODE, "request-1");
  }

  /** 测试意图：UNKNOWN 只能由人工核查解除，resume 让 Run 回到 READY 且核查事实原样传给应用服务。 */
  @Test
  void resolveUnknownWithResumeReturnsSchedulableRun() throws Exception {
    when(runtimeService.resolve(
            CANVAS, NODE, "request-1", CanvasFunctionUnknownResolution.RESUME, VERIFICATION))
        .thenReturn(run(CanvasFunctionRunStatus.READY, "SUBMITTED"));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"request-1\",\"resolution\":\"RESUME\",\"verification\":\""
                        + VERIFICATION
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("READY"))
        .andExpect(jsonPath("$.data.stage").value("SUBMITTED"));
    verify(runtimeService)
        .resolve(CANVAS, NODE, "request-1", CanvasFunctionUnknownResolution.RESUME, VERIFICATION);
  }

  /** 测试意图：人工核查也可以确认失败或取消，两种终态都原样回传应用服务结果。 */
  @Test
  void resolveUnknownWithTerminalResolutionsReturnsTerminalRun() throws Exception {
    when(runtimeService.resolve(
            CANVAS, NODE, "request-1", CanvasFunctionUnknownResolution.FAILED, VERIFICATION))
        .thenReturn(run(CanvasFunctionRunStatus.FAILED, "FAILED"));
    when(runtimeService.resolve(
            CANVAS, NODE, "request-1", CanvasFunctionUnknownResolution.CANCELLED, VERIFICATION))
        .thenReturn(run(CanvasFunctionRunStatus.CANCELLED, "CANCELLED"));

    mockMvc
        .perform(resolve("FAILED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("FAILED"));
    mockMvc
        .perform(resolve("CANCELLED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
  }

  /** 测试意图：非法 resolution 与缺失必填核查事实都在边界处被拒绝，不会触达应用服务。 */
  @Test
  void rejectsInvalidResolutionAndBlankVerification() throws Exception {
    mockMvc.perform(resolve("resume")).andExpect(status().isBadRequest());
    verify(runtimeService, never()).resolve(any(), any(), any(), any(), any());

    // 缺失与纯空白核查事实由应用服务拒绝，边界统一映射为 400 BadRequest。
    when(runtimeService.resolve(any(), any(), anyString(), any(), any()))
        .thenThrow(new IllegalArgumentException("verification must not be blank"));
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"request-1\",\"resolution\":\"RESUME\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"request-1\",\"resolution\":\"RESUME\",\"verification\":\" \"}"))
        .andExpect(status().isBadRequest());
  }

  /** 测试意图：resolve 请求体同样严格，未知字段不会被静默忽略。 */
  @Test
  void rejectsUnknownResolveFields() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"request-1\",\"resolution\":\"RESUME\",\"verification\":\"v\","
                        + "\"extra\":true}"))
        .andExpect(status().isBadRequest());
    verify(runtimeService, never()).resolve(any(), any(), any(), any(), any());
  }

  /**
   * 测试意图：验证 POST /api/canvases/{canvasId}/nodes/{nodeId}/function-run 严格校验 UUID 格式与非重复请求体，返回 400
   * BadRequest。
   */
  @Test
  void rejectsUnknownDuplicateAndNonCanonicalIds() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\",\"extra\":true}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\",\"requestId\":\"r\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/not-a-uuid/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"r\",\"expectedVersion\":{"
                        + "\"definitionFingerprint\":\""
                        + expectedVersion().definitionFingerprint()
                        + "\"}}"))
        .andExpect(status().isBadRequest());
    // expectedVersion 内部的未知字段同样被拒绝，不能静默忽略。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"r\",\"expectedVersion\":{"
                        + "\"definitionFingerprint\":\""
                        + expectedVersion().definitionFingerprint()
                        + "\",\"extra\":true}}"))
        .andExpect(status().isBadRequest());
  }

  /** 测试意图：验证业务异常精准映射为 404 NotFound 与 409 Conflict 状态码，UNKNOWN 未核查前不可再启动或取消。 */
  @Test
  void mapsNotFoundAndConflictPrecisely() throws Exception {
    when(runtimeService.get(any(), any()))
        .thenThrow(
            new CanvasFunctionRunException(CanvasFunctionRunException.Reason.NOT_FOUND, "missing"));
    when(runtimeService.start(any(), any(), anyString(), any()))
        .thenThrow(
            new CanvasFunctionRunException(CanvasFunctionRunException.Reason.CONFLICT, "running"));
    when(runtimeService.cancel(any(), any(), anyString()))
        .thenThrow(
            new CanvasFunctionRunException(
                CanvasFunctionRunException.Reason.CONFLICT,
                "UNKNOWN FunctionRun must be resolved, not cancelled"));
    when(runtimeService.resolve(any(), any(), anyString(), any(), any()))
        .thenThrow(
            new CanvasFunctionRunException(
                CanvasFunctionRunException.Reason.CONFLICT, "only an UNKNOWN FunctionRun"));

    mockMvc
        .perform(get("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\"r\",\"expectedVersion\":{"
                        + "\"definitionFingerprint\":\""
                        + expectedVersion().definitionFingerprint()
                        + "\"}}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\"}"))
        .andExpect(status().isConflict());
    mockMvc.perform(resolve("RESUME")).andExpect(status().isConflict());
  }

  /** 测试意图：验证无旧 alias，历史旧路径 /runs、/run、/run/cancel 均返回 404 NotFound。 */
  @Test
  void oldPathAliasesAreNotPresent() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\"}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/run"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/run/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\"}"))
        .andExpect(status().isNotFound());
  }

  private static MockHttpServletRequestBuilder resolve(String resolution) {
    return post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run/resolve")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"requestId\":\"request-1\",\"resolution\":\""
                + resolution
                + "\",\"verification\":\""
                + VERIFICATION
                + "\"}");
  }

  private static JacksonJsonHttpMessageConverter strictJsonConverter() {
    return new JacksonJsonHttpMessageConverter(
        JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  }

  private static CanvasFunctionRun run(CanvasFunctionRunStatus status, String stage) {
    return new CanvasFunctionRun(
        NODE,
        REQUEST,
        status,
        status == CanvasFunctionRunStatus.READY ? 0 : 1,
        stage,
        "{\"stage\":\"" + stage + "\",\"secret\":\"hidden\"}",
        status == CanvasFunctionRunStatus.FAILED || status == CanvasFunctionRunStatus.UNKNOWN
            ? "external task failed"
            : null,
        NOW,
        NOW);
  }

  /** 严格 JSON object schema：显式声明 prompt 字符串属性与 additionalProperties=false。 */
  private static CanvasJson.JsonObject argsSchema() {
    return new CanvasJson.JsonObject(
        Map.of(
            "type",
            new CanvasJson.JsonText("object"),
            "properties",
            new CanvasJson.JsonObject(
                Map.of(
                    "prompt",
                    new CanvasJson.JsonObject(Map.of("type", new CanvasJson.JsonText("string"))))),
            "required",
            new CanvasJson.JsonArray(List.of(new CanvasJson.JsonText("prompt"))),
            "additionalProperties",
            new CanvasJson.JsonBool(false)));
  }

  /** 「客户端看到的版本」由同一函数定义确定性构造。 */
  private static CanvasFunctionVersion expectedVersion() {
    CanvasFunctionDefinition definition = adapter("fake-image", true, null).functions().get(0);
    return new CanvasFunctionVersion(CanvasFunctionCatalog.definitionFingerprint(definition));
  }

  /** 以函数名声明能力的适配器；执行细节与本测试无关。 */
  private static CanvasFunctionAdapter adapter(
      String name, boolean enabled, String unavailableReason) {
    CanvasFunctionDefinition definition =
        new CanvasFunctionDefinition(
            name,
            "Fake Image",
            argsSchema(),
            List.of(new CanvasFunctionOutputSpec(CanvasResourceKind.IMAGE, null)),
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 12, Map.of()));
    return new CanvasFunctionAdapter() {
      @Override
      public List<CanvasFunctionDefinition> functions() {
        return List.of(definition);
      }

      @Override
      public boolean enabled() {
        return enabled;
      }

      @Override
      public String unavailableReason() {
        return unavailableReason;
      }

      @Override
      public void preflight(CanvasFunctionFrozenRun run) {}

      @Override
      public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {}

      @Override
      public List<UUID> execute(
          CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** 测试替身：固定目录，并记录未关闭的租用数。 */
  private static final class TestCanvasFunctionCatalogSource
      implements CanvasFunctionCatalogSource {

    private final CanvasFunctionCatalog catalog;
    private int openLeases;

    private TestCanvasFunctionCatalogSource(CanvasFunctionCatalog catalog) {
      this.catalog = catalog;
    }

    private int openLeases() {
      return openLeases;
    }

    @Override
    public Lease acquire() {
      openLeases++;
      return new Lease() {
        private boolean closed;

        @Override
        public CanvasFunctionCatalog catalog() {
          return catalog;
        }

        @Override
        public CanvasFunctionBinding bind(String functionName) {
          return new CanvasFunctionBinding(catalog.require(functionName).function());
        }

        @Override
        public CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding) {
          CanvasFunctionCatalog.RegisteredFunction registered =
              catalog.require(binding.definition().name());
          if (!registered.function().equals(binding.definition())) {
            throw new IllegalStateException("binding does not match the acquired catalog");
          }
          return registered;
        }

        @Override
        public void close() {
          if (!closed) {
            closed = true;
            openLeases--;
          }
        }
      };
    }
  }
}
