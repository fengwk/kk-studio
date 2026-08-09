package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.share.studio.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.studio.CanvasCommandDTO;
import fun.fengwk.kkstudio.share.studio.CanvasTransformDTO;
import fun.fengwk.kkstudio.share.studio.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Canvas HTTP typed DTO、严格字符串和状态码映射。 */
class StudioCanvasControllerTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private CanvasQueryService queryService;
  private CanvasCommandService commandService;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    queryService = Mockito.mock(CanvasQueryService.class);
    commandService = Mockito.mock(CanvasCommandService.class);
    mockMvc =
        standaloneSetup(new StudioCanvasController(queryService, commandService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void listAndSnapshotExposeResourceFunctionGroupAndIdentityContracts() throws Exception {
    when(queryService.listDocuments()).thenReturn(List.of(document()));
    when(queryService.findSnapshot(42L)).thenReturn(Optional.of(snapshot()));

    mockMvc
        .perform(get("/api/canvases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("42"))
        .andExpect(jsonPath("$.data[0].graphRevision").value("3"))
        .andExpect(jsonPath("$.data[0].homeViewportJson").doesNotExist());

    mockMvc
        .perform(get("/api/canvases/42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.nodes[0].resources[0].id").value("201"))
        .andExpect(jsonPath("$.data.nodes[1].function.modelKey").value("model"))
        .andExpect(jsonPath("$.data.groups[0].id").value("301"))
        .andExpect(jsonPath("$.data.links[0].canvasId").value("42"))
        .andExpect(jsonPath("$.data.links[0].id").doesNotExist());
  }

  @Test
  void createKeepsExistingRouteWithNewDocumentContract() throws Exception {
    when(commandService.createCanvas(any())).thenReturn(document());
    CreateCanvasRequestDTO body = new CreateCanvasRequestDTO();
    body.setTitle("board");

    mockMvc
        .perform(
            post("/api/canvases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("CREATED"))
        .andExpect(jsonPath("$.data.graphRevision").value("3"));
    verify(commandService).createCanvas("board");
  }

  @Test
  void typedCommandBodyMapsToDomainRecords() throws Exception {
    when(commandService.applyCommands(anyLong(), anyLong(), any(), anyList()))
        .thenReturn(snapshot());
    ApplyCanvasCommandsRequestDTO request = new ApplyCanvasCommandsRequestDTO();
    request.setExpectedRevision("3");
    request.setCommandId("cmd-1");
    request.setCommands(
        List.of(
            new CanvasCommandDTO.CreateTextNode(
                "note", "hello", new CanvasTransformDTO(1, 2, 100, 80))));

    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isOk());

    verify(commandService)
        .applyCommands(
            eq(42L),
            eq(3L),
            eq("cmd-1"),
            eq(
                List.of(
                    new CanvasCommand.CreateTextNode(
                        "note", "hello", new CanvasTransform(1, 2, 100, 80)))));
  }

  @Test
  void revisionAndIdempotencyConflictsMapTo409() throws Exception {
    when(commandService.applyCommands(anyLong(), anyLong(), any(), anyList()))
        .thenThrow(new CanvasConflictException(CanvasConflictException.Reason.REVISION_CONFLICT));

    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCommandJson()))
        .andExpect(status().isConflict());
  }

  @Test
  void idsAndRevisionRequireCanonicalDecimalStrings() throws Exception {
    mockMvc.perform(get("/api/canvases/0")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/canvases/01")).andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCommandJson().replace("\"3\"", "\"03\"")))
        .andExpect(status().isBadRequest());
    verify(commandService, never()).applyCommands(anyLong(), anyLong(), any(), anyList());
  }

  @Test
  void unknownFieldsAndUnknownCommandTypesAreRejectedByJackson() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedRevision":"3","commandId":"c","extra":true,
                     "commands":[{"type":"DELETE_NODE","nodeId":"1"}]}
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedRevision":"3","commandId":"c",
                     "commands":[{"type":"OLD_COMMAND"}]}
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedRevision":"3","commandId":"c",
                     "commands":[{"type":"DELETE_NODE","nodeId":"1","legacy":true}]}
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/42/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedRevision":"3","commandId":"c",
                     "commands":[{"type":"CREATE_TEXT_NODE","name":"n","markdown":"x",
                       "transform":{"x":0,"y":0,"width":1,"height":1,"legacy":true}}]}
                    """))
        .andExpect(status().isBadRequest());
  }

  private String validCommandJson() {
    return """
        {"expectedRevision":"3","commandId":"c",
         "commands":[{"type":"DELETE_NODE","nodeId":"101"}]}
        """;
  }

  private CanvasDocument document() {
    return new CanvasDocument(42, "demo", 3, NOW, NOW);
  }

  private CanvasSnapshot snapshot() {
    CanvasResource resource =
        new CanvasResource(
            201, 42, CanvasResourceKind.TEXT, "text/markdown", "note", 5, "hello", "{}", NOW);
    CanvasResourceNode ordinary =
        new CanvasResourceNode(
            101,
            42,
            "note",
            new CanvasTransform(1, 2, 100, 80),
            301L,
            List.of(resource),
            null,
            null);
    CanvasResourceNode function =
        new CanvasResourceNode(
            102,
            42,
            "fn",
            new CanvasTransform(200, 2, 100, 80),
            null,
            List.of(),
            new CanvasFunction("model", "{}"),
            null);
    return new CanvasSnapshot(
        document(),
        List.of(ordinary, function),
        List.of(new CanvasGroup(301, 42, "group", new CanvasTransform(0, 0, 400, 200))),
        List.of(new CanvasLink(42, 101, 102)));
  }
}
