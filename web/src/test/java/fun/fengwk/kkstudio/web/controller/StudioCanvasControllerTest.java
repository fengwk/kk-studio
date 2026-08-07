package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.share.studio.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.studio.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodeKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.NodeTransform;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link StudioCanvasController} 的 web 层覆盖。
 *
 * <p>持久化适配器已由 {@code DurableCanvasServiceTest} 端到端覆盖；本测试仅验证 HTTP 映射与状态码翻译。
 */
public class StudioCanvasControllerTest {

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private CanvasQueryService canvasQueryService;
  private CanvasCommandService canvasCommandService;

  @BeforeEach
  public void setUp() {
    objectMapper = new ObjectMapper();
    canvasQueryService = Mockito.mock(CanvasQueryService.class);
    canvasCommandService = Mockito.mock(CanvasCommandService.class);
    MappingJackson2HttpMessageConverter converter =
        new MappingJackson2HttpMessageConverter(objectMapper);
    mockMvc =
        standaloneSetup(new StudioCanvasController(canvasQueryService, canvasCommandService))
            .setMessageConverters(converter)
            .build();
  }

  private CanvasDocument sampleDocument() {
    return new CanvasDocument(42L, "demo", 0L, "{\"x\":80,\"y\":20,\"scale\":0.6}");
  }

  private CanvasSnapshot sampleSnapshot() {
    return new CanvasSnapshot(
        sampleDocument(),
        List.of(
            new CanvasNode(
                101L,
                CanvasNodeKind.RESOURCE,
                "text",
                "hello",
                new NodeTransform(0d, 0d, 100d, 100d),
                "{\"text\":\"hi\"}")),
        List.of());
  }

  @Test
  public void listReturnsCanvasDocumentDtosWithoutRemovedFields() throws Exception {
    when(canvasQueryService.listDocuments()).thenReturn(List.of(sampleDocument()));

    mockMvc
        .perform(get("/api/canvases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("OK"))
        .andExpect(jsonPath("$.data[0].id").value("42"))
        .andExpect(jsonPath("$.data[0].title").value("demo"))
        .andExpect(jsonPath("$.data[0].revision").value("0"))
        .andExpect(
            jsonPath("$.data[0].homeViewportJson").value("{\"x\":80,\"y\":20,\"scale\":0.6}"));
  }

  @Test
  public void getReturnsSnapshotAndMaps404WhenCanvasMissing() throws Exception {
    when(canvasQueryService.findSnapshot(99L)).thenReturn(Optional.of(sampleSnapshot()));

    mockMvc
        .perform(get("/api/canvases/99"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.document.id").value("42"))
        .andExpect(jsonPath("$.data.nodes[0].id").value("101"))
        .andExpect(jsonPath("$.data.nodes[0].kind").value("RESOURCE"));

    when(canvasQueryService.findSnapshot(404L)).thenReturn(Optional.empty());
    mockMvc.perform(get("/api/canvases/404")).andExpect(status().isNotFound());
  }

  @Test
  public void createMapsMissingAndExplicitBodies() throws Exception {
    when(canvasCommandService.createCanvas(any())).thenReturn(sampleDocument());

    mockMvc
        .perform(post("/api/canvases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("CREATED"));

    CreateCanvasRequestDTO body = new CreateCanvasRequestDTO();
    body.setTitle("board");
    mockMvc
        .perform(
            post("/api/canvases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("CREATED"))
        .andExpect(jsonPath("$.data.id").value("42"));
    verify(canvasCommandService).createCanvas("board");
  }

  @Test
  public void createMapsIllegalArgumentTo400() throws Exception {
    when(canvasCommandService.createCanvas(any()))
        .thenThrow(new IllegalArgumentException("title must not be blank"));

    CreateCanvasRequestDTO body = new CreateCanvasRequestDTO();
    body.setTitle(" ");

    mockMvc
        .perform(
            post("/api/canvases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void applyCommandsReturnsSnapshotOnSuccess() throws Exception {
    when(canvasCommandService.applyCommands(anyLong(), anyLong(), any(), any()))
        .thenReturn(sampleSnapshot());

    ApplyCanvasCommandsRequestDTO body = new ApplyCanvasCommandsRequestDTO();
    body.setBaseRevision("0");
    body.setCommandId("cmd-1");
    body.setCommandsJson(
        "[{\"type\":\"create_text_node\",\"name\":\"hi\",\"text\":\"hello\",\"x\":0,\"y\":0,"
            + "\"width\":120,\"height\":80}]");

    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.document.id").value("42"))
        .andExpect(jsonPath("$.data.nodes[0].kind").value("RESOURCE"));

    verify(canvasCommandService)
        .applyCommands(eq(42L), eq(0L), eq("cmd-1"), eq(body.getCommandsJson()));
  }

  @Test
  public void applyCommandsMapsBadRevisionTo400() throws Exception {
    Map<String, String> body =
        Map.of("baseRevision", "not-a-number", "commandId", "c", "commandsJson", "[]");
    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isBadRequest());
    verify(canvasCommandService, never()).applyCommands(anyLong(), anyLong(), any(), any());
  }

  @Test
  public void applyCommandsMapsBadCommandsJsonTo400() throws Exception {
    when(canvasCommandService.applyCommands(anyLong(), anyLong(), any(), any()))
        .thenThrow(new IllegalArgumentException("commandsJson must be a JSON array"));
    ApplyCanvasCommandsRequestDTO body = new ApplyCanvasCommandsRequestDTO();
    body.setBaseRevision("0");
    body.setCommandId("c");
    body.setCommandsJson("{}");

    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void applyCommandsMapsRevisionConflictTo409() throws Exception {
    when(canvasCommandService.applyCommands(anyLong(), anyLong(), any(), any()))
        .thenThrow(new IllegalStateException("REVISION_CONFLICT"));
    ApplyCanvasCommandsRequestDTO body = new ApplyCanvasCommandsRequestDTO();
    body.setBaseRevision("0");
    body.setCommandId("c");
    body.setCommandsJson("[]");

    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isConflict());
  }

  @Test
  public void applyCommandsMapsIdempotencyConflictTo409() throws Exception {
    when(canvasCommandService.applyCommands(anyLong(), anyLong(), any(), any()))
        .thenThrow(new IllegalStateException("IDEMPOTENCY_CONFLICT"));
    ApplyCanvasCommandsRequestDTO body = new ApplyCanvasCommandsRequestDTO();
    body.setBaseRevision("0");
    body.setCommandId("c");
    body.setCommandsJson("[]");

    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isConflict());
  }

  @Test
  public void applyCommandsMissingBaseRevisionReturns400() throws Exception {
    Map<String, String> body = Map.of("commandId", "c", "commandsJson", "[]");
    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void getMapsLinks() throws Exception {
    CanvasSnapshot withLink =
        new CanvasSnapshot(
            sampleDocument(), sampleSnapshot().nodes(), List.of(new CanvasLink(900L, 101L, 102L)));
    when(canvasQueryService.findSnapshot(42L)).thenReturn(Optional.of(withLink));

    mockMvc
        .perform(get("/api/canvases/42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.links[0].id").value("900"))
        .andExpect(jsonPath("$.data.links[0].sourceNodeId").value("101"))
        .andExpect(jsonPath("$.data.links[0].targetNodeId").value("102"));
  }
}
