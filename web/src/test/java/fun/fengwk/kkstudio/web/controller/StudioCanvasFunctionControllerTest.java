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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionModelRegistry;
import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionRuntimeService;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.web.storage.FixedObjectProvider;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

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
  private CanvasFunctionRuntimeService runtimeService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtimeService = mock(CanvasFunctionRuntimeService.class);
    CanvasFunctionModelRegistry registry =
        new CanvasFunctionModelRegistry(List.of(adapter("fake-image")));
    FixedObjectProvider<StorageBlobManager> blobManagers =
        new FixedObjectProvider<>(mock(StorageBlobManager.class));
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    mockMvc =
        standaloneSetup(
                new StudioCanvasFunctionController(
                    registry, runtimeService, new StudioWebMapper(blobManagers)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

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

  @Test
  void startGetCancelExposeOnlyPublicRunFields() throws Exception {
    CanvasFunctionRun run = run(CanvasFunctionRunStatus.RUNNING, "QUEUED");
    when(runtimeService.start(CANVAS, NODE, "request-1")).thenReturn(run);
    when(runtimeService.get(CANVAS, NODE)).thenReturn(run);
    when(runtimeService.cancel(CANVAS, NODE, "request-1"))
        .thenReturn(run(CanvasFunctionRunStatus.CANCELLED, "CANCELLED"));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"request-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.stage").value("QUEUED"))
        .andExpect(jsonPath("$.data.stateJson").doesNotExist());
    mockMvc
        .perform(get("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.requestId").value(REQUEST.toString()));
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/run/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"request-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    verify(runtimeService).start(CANVAS, NODE, "request-1");
    verify(runtimeService).cancel(CANVAS, NODE, "request-1");
  }

  @Test
  void rejectsUnknownDuplicateAndNonCanonicalIds() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\",\"extra\":true}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\",\"requestId\":\"r\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/not-a-uuid/nodes/" + NODE + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mapsNotFoundAndConflictPrecisely() throws Exception {
    when(runtimeService.get(any(), any()))
        .thenThrow(
            new CanvasFunctionRunException(CanvasFunctionRunException.Reason.NOT_FOUND, "missing"));
    when(runtimeService.start(any(), any(), anyString()))
        .thenThrow(
            new CanvasFunctionRunException(CanvasFunctionRunException.Reason.CONFLICT, "running"));

    mockMvc
        .perform(get("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/run"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/nodes/" + NODE + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"r\"}"))
        .andExpect(status().isConflict());
  }

  private static CanvasFunctionRun run(CanvasFunctionRunStatus status, String stage) {
    return new CanvasFunctionRun(
        NODE,
        REQUEST,
        status,
        stage,
        "{\"stage\":\"" + stage + "\",\"secret\":\"hidden\"}",
        null,
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
