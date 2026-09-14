package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.web.mapper.WebDtoMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Function model/run HTTP 契约、状态码与 stateJson 隔离。 */
class StudioCanvasFunctionControllerTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final UUID CANVAS = new UUID(0L, 1L);
  private static final UUID NODE = new UUID(0L, 2L);
  private static final UUID REQUEST = new UUID(0L, 3L);

  private MockMvc mockMvc;
  private CanvasFunctionService runtimeService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtimeService = mock(CanvasFunctionService.class);
    CanvasFunctionAdapter adapter = adapter("fake-image");
    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(List.of(adapter));
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    mockMvc =
        standaloneSetup(
                new StudioCanvasFunctionController(
                    catalog, runtimeService, new WebDtoMapper(blobManager)))
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

  /** 测试意图：验证 GET /api/canvas-function-models 返回模型元数据且不泄露后端实现细节。 */
  @Test
  void listsCapabilitiesWithoutProviderInternals() throws Exception {
    mockMvc
        .perform(get("/api/canvas-function-models"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].key").value("fake-image"))
        .andExpect(jsonPath("$.data[0].outputKind").value("IMAGE"))
        .andExpect(jsonPath("$.data[0].available").value(true))
        .andExpect(jsonPath("$.data[0].endpoint").doesNotExist())
        .andExpect(jsonPath("$.data[0].workflow").doesNotExist());
  }

  /**
   * 测试意图：验证 Canvas Function 统一使用 function-run 路径，POST 启动返回 202 Accepted，GET 查询与 POST 取消返回 200
   * OK，且字段脱敏。
   */
  @Test
  void startGetCancelExposeOnlyPublicRunFields() throws Exception {
    CanvasFunctionRun run = run(CanvasFunctionRunStatus.READY, "QUEUED");
    when(runtimeService.start(CANVAS, NODE, "request-1")).thenReturn(run);
    when(runtimeService.get(CANVAS, NODE)).thenReturn(run);
    when(runtimeService.cancel(CANVAS, NODE, "request-1"))
        .thenReturn(run(CanvasFunctionRunStatus.CANCELLED, "CANCELLED"));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"request-1\"}"))
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
    verify(runtimeService).start(CANVAS, NODE, "request-1");
    verify(runtimeService).cancel(CANVAS, NODE, "request-1");
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
                .content("{\"requestId\":\"r\"}"))
        .andExpect(status().isBadRequest());
  }

  /** 测试意图：验证业务异常精准映射为 404 NotFound 与 409 Conflict 状态码。 */
  @Test
  void mapsNotFoundAndConflictPrecisely() throws Exception {
    when(runtimeService.get(any(), any()))
        .thenThrow(
            new CanvasFunctionRunException(CanvasFunctionRunException.Reason.NOT_FOUND, "missing"));
    when(runtimeService.start(any(), any(), anyString()))
        .thenThrow(
            new CanvasFunctionRunException(CanvasFunctionRunException.Reason.CONFLICT, "running"));

    mockMvc
        .perform(get("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/function-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\"}"))
        .andExpect(status().isConflict());
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

  private static CanvasFunctionRun run(CanvasFunctionRunStatus status, String stage) {
    return new CanvasFunctionRun(
        NODE,
        REQUEST,
        status,
        status == CanvasFunctionRunStatus.READY ? 0 : 1,
        status == CanvasFunctionRunStatus.READY ? NOW : null,
        status == CanvasFunctionRunStatus.RUNNING ? "lease" : null,
        status == CanvasFunctionRunStatus.RUNNING ? NOW.plusSeconds(30) : null,
        stage,
        "{\"stage\":\"" + stage + "\",\"secret\":\"hidden\"}",
        null,
        NOW,
        NOW);
  }

  private static CanvasFunctionAdapter adapter(String key) {
    CanvasFunctionModel model =
        new CanvasFunctionModel(
            key,
            "Fake Image",
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 12, Map.of()),
            List.of());
    return new CanvasFunctionAdapter() {
      @Override
      public List<CanvasFunctionModel> models() {
        return List.of(model);
      }

      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public String unavailableReason() {
        return null;
      }

      @Override
      public void preflight(CanvasFunctionFrozenRun run) {}

      @Override
      public List<UUID> execute(
          CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
