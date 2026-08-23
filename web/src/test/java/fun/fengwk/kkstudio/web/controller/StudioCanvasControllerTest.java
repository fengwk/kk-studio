package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.common.json.jackson.ObjectMapperHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.core.studio.StudioHarnessQueryService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.studio.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.studio.CanvasCommandDTO;
import fun.fengwk.kkstudio.share.studio.CanvasTransformDTO;
import fun.fengwk.kkstudio.share.studio.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasLinkPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.web.storage.FixedObjectProvider;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Canvas HTTP typed DTO、canonical UUID 字符串、version 与状态码映射。 */
class StudioCanvasControllerTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final UUID CANVAS = new UUID(0L, 1L);
  private static final UUID NODE_1 = new UUID(0L, 2L);
  private static final UUID NODE_2 = new UUID(0L, 3L);
  private static final UUID RESOURCE_1 = new UUID(0L, 4L);
  private static final UUID BLOB_1 = new UUID(0L, 5L);
  private static final UUID GROUP = new UUID(0L, 6L);
  private static final UUID COMMAND = new UUID(0L, 8L);
  private static final UUID REQUEST = new UUID(0L, 9L);

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private CanvasQueryService queryService;
  private CanvasCommandService commandService;
  private StudioHarnessQueryService harnessQueryService;
  private StorageBlobManager blobManager;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    // 使用 convention4j 生产 ObjectMapper：long/Long 输出十进制字符串、默认 NON_NULL、FAIL_ON_UNKNOWN_PROPERTIES
    // 关闭（未知字段由 DTO @JsonAnySetter 拒绝），与真实 wire 一致。
    objectMapper = ObjectMapperHolder.getInstance();
    queryService = mock(CanvasQueryService.class);
    commandService = mock(CanvasCommandService.class);
    harnessQueryService = mock(StudioHarnessQueryService.class);
    blobManager = mock(StorageBlobManager.class);
    FixedObjectProvider<StorageBlobManager> blobManagers = new FixedObjectProvider<>(blobManager);
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB_1);
    blob.setMediaType("image/png");
    blob.setSizeBytes(5L);
    blob.setWidth(640L);
    blob.setHeight(480L);
    when(blobManager.getBlob(BLOB_1)).thenReturn(blob);
    mockMvc =
        standaloneSetup(
                new StudioCanvasController(
                    queryService,
                    commandService,
                    harnessQueryService,
                    new StudioWebMapper(blobManagers)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void listAndSnapshotExposeUuidVersionAndBlobFactsContracts() throws Exception {
    when(queryService.listDocuments()).thenReturn(List.of(document()));
    when(queryService.findSnapshot(CANVAS)).thenReturn(Optional.of(snapshot()));

    MvcResult list =
        mockMvc
            .perform(get("/api/canvases"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(CANVAS.toString()))
            .andExpect(jsonPath("$.data[0].version").value("3"))
            .andExpect(jsonPath("$.data[0].graphVersion").doesNotExist())
            .andReturn();
    JsonNode listJson = readTree(list);
    assertEquals(
        true, listJson.at("/data/0/version").isTextual(), "version wire must be a JSON string");
    assertEquals("3", listJson.at("/data/0/version").asText());

    MvcResult snapshotResult =
        mockMvc
            .perform(get("/api/canvases/" + CANVAS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.document.version").value("3"))
            .andExpect(jsonPath("$.data.nodes[0].id").value(NODE_1.toString()))
            .andExpect(jsonPath("$.data.nodes[0].resources[0].id").value(RESOURCE_1.toString()))
            .andExpect(jsonPath("$.data.nodes[0].resources[0].blobId").value(BLOB_1.toString()))
            .andExpect(jsonPath("$.data.nodes[0].resources[0].kind").value("IMAGE"))
            .andExpect(jsonPath("$.data.nodes[0].resources[0].mediaType").value("image/png"))
            .andExpect(jsonPath("$.data.nodes[0].resources[0].sizeBytes").value("5"))
            .andExpect(jsonPath("$.data.nodes[0].resources[0].width").value(640))
            .andExpect(jsonPath("$.data.nodes[0].resources[0].height").value(480))
            .andExpect(jsonPath("$.data.nodes[0].resources[0].durationMs").value(nullValue()))
            .andExpect(jsonPath("$.data.nodes[0].groupId").value(GROUP.toString()))
            .andExpect(jsonPath("$.data.nodes[0].function").value(nullValue()))
            .andExpect(jsonPath("$.data.nodes[0].run").value(nullValue()))
            .andExpect(jsonPath("$.data.nodes[1].function.modelKey").value("model"))
            .andExpect(jsonPath("$.data.nodes[1].groupId").value(nullValue()))
            .andExpect(jsonPath("$.data.nodes[1].run.stage").value("QUEUED"))
            .andExpect(jsonPath("$.data.nodes[1].run.requestId").value(REQUEST.toString()))
            .andExpect(jsonPath("$.data.nodes[1].run.error").value(nullValue()))
            .andExpect(jsonPath("$.data.nodes[1].run.stateJson").doesNotExist())
            .andExpect(jsonPath("$.data.groups[0].id").value(GROUP.toString()))
            .andExpect(jsonPath("$.data.links[0].canvasId").value(CANVAS.toString()))
            .andExpect(jsonPath("$.data.links[0].sourceNodeId").value(NODE_1.toString()))
            .andExpect(jsonPath("$.data.links[0].id").doesNotExist())
            .andReturn();
    JsonNode json = readTree(snapshotResult);
    assertTextual(json, "/data/document/version", "3");
    assertTextual(json, "/data/nodes/0/resources/0/sizeBytes", "5");
    assertNullPresent(json, "/data/nodes/0/resources/0/durationMs");
    assertNullPresent(json, "/data/nodes/0/function");
    assertNullPresent(json, "/data/nodes/0/run");
    assertNullPresent(json, "/data/nodes/1/run/error");
  }

  @Test
  void listCanvasSessionsReturnsOwnerSummaries() throws Exception {
    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(new UUID(0L, 10L).toString());
    summary.setCreatedAt(NOW);
    summary.setLastActivityAt(NOW.plusSeconds(3));
    summary.setFirstMessagePreview("canvas prompt");
    summary.setThreadCount(1);
    when(harnessQueryService.listCanvasSessions(CANVAS)).thenReturn(List.of(summary));

    mockMvc
        .perform(get("/api/canvases/" + CANVAS + "/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sessionId").value(summary.getSessionId()))
        .andExpect(jsonPath("$.data[0].firstMessagePreview").value("canvas prompt"))
        .andExpect(jsonPath("$.data[0].threadCount").value(1));
    verify(harnessQueryService).listCanvasSessions(CANVAS);
  }

  @Test
  void textResourceHasNoBlobFactsAndCreateKeepsDocumentContract() throws Exception {
    when(queryService.findSnapshot(CANVAS))
        .thenReturn(
            Optional.of(
                new CanvasSnapshot(
                    new CanvasDocument(CANVAS, "demo", 3, NOW, NOW),
                    List.of(
                        new CanvasResourceNode(
                            NODE_1,
                            CANVAS,
                            "note",
                            new CanvasTransform(1, 2, 100, 80),
                            null,
                            List.of(
                                new CanvasResource(
                                    RESOURCE_1, CANVAS, NODE_1, 0, null, "note", "hello", NOW)),
                            null,
                            null)),
                    List.of(),
                    List.of())));
    when(commandService.createCanvas(any())).thenReturn(document());

    mockMvc
        .perform(get("/api/canvases/" + CANVAS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.document.version").value("3"))
        .andExpect(jsonPath("$.data.nodes[0].resources[0].kind").value("TEXT"))
        .andExpect(jsonPath("$.data.nodes[0].resources[0].textContent").value("hello"))
        .andExpect(jsonPath("$.data.nodes[0].resources[0].blobId").value(nullValue()))
        .andExpect(jsonPath("$.data.nodes[0].resources[0].mediaType").value(nullValue()))
        .andExpect(jsonPath("$.data.nodes[0].resources[0].sizeBytes").value(nullValue()))
        .andExpect(jsonPath("$.data.nodes[0].resources[0].width").value(nullValue()))
        .andExpect(jsonPath("$.data.nodes[0].resources[0].height").value(nullValue()))
        .andExpect(jsonPath("$.data.nodes[0].resources[0].durationMs").value(nullValue()));

    CreateCanvasRequestDTO body = new CreateCanvasRequestDTO();
    body.setTitle("board");
    mockMvc
        .perform(
            post("/api/canvases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("CREATED"))
        .andExpect(jsonPath("$.data.version").value("3"))
        .andExpect(jsonPath("$.data.id").value(CANVAS.toString()));
    verify(commandService).createCanvas("board");

    mockMvc
        .perform(post("/api/canvases").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
    verify(commandService).createCanvas(null);
  }

  @Test
  void typedCommandBodyMapsToDomainRecords() throws Exception {
    when(commandService.applyCommands(any(UUID.class), anyLong(), any(UUID.class), anyList()))
        .thenReturn(patch());
    ApplyCanvasCommandsRequestDTO request = new ApplyCanvasCommandsRequestDTO();
    request.setExpectedVersion("2");
    request.setCommandId(COMMAND.toString());
    request.setCommands(
        List.of(
            new CanvasCommandDTO.CreateTextNode(
                NODE_1.toString(), "note", "hello", new CanvasTransformDTO(1, 2, 100, 80)),
            new CanvasCommandDTO.CreateLink(NODE_1.toString(), NODE_2.toString()),
            new CanvasCommandDTO.RenameGroup(GROUP.toString(), "renamed group")));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.baseVersion").value("2"))
        .andExpect(jsonPath("$.data.version").value("3"))
        .andExpect(jsonPath("$.data.nodes[0].op").value("REMOVE"))
        .andExpect(jsonPath("$.data.nodes[0].nodeId").value(NODE_2.toString()));

    verify(commandService)
        .applyCommands(
            eq(CANVAS),
            eq(2L),
            eq(COMMAND),
            eq(
                List.of(
                    new CanvasCommand.CreateTextNode(
                        NODE_1, "note", "hello", new CanvasTransform(1, 2, 100, 80)),
                    new CanvasCommand.CreateLink(NODE_1, NODE_2),
                    new CanvasCommand.RenameGroup(GROUP, "renamed group"))));
  }

  @Test
  void expectedVersionRequiresCanonicalNonNegativeDecimal() throws Exception {
    when(commandService.applyCommands(any(UUID.class), anyLong(), any(UUID.class), anyList()))
        .thenReturn(patch());
    String commands =
        "\"commandId\":\"%s\",\"commands\":[{\"type\":\"DELETE_NODE\",\"nodeId\":\"%s\"}]"
            .formatted(COMMAND, NODE_1);

    // 公共契约是 string；JSON number 经 Jackson coercion 兼容接受（同一严格校验）。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"," + commands + "}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3," + commands + "}"))
        .andExpect(status().isOk());

    for (String invalid :
        new String[] {
          "\"-1\"", // 负数
          "\"01\"", // leading zero
          "\"abc\"", // 非数字
          "\"9223372036854775808\"", // 超 long 范围
          "null" // 缺失
        }) {
      mockMvc
          .perform(
              post("/api/canvases/" + CANVAS + "/commands")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"expectedVersion\":" + invalid + "," + commands + "}"))
          .andExpect(status().isBadRequest());
    }

    verify(commandService, times(2)).applyCommands(eq(CANVAS), eq(3L), eq(COMMAND), anyList());
  }

  @Test
  void versionAndIdempotencyConflictsMapTo409() throws Exception {
    when(commandService.applyCommands(any(UUID.class), anyLong(), any(UUID.class), anyList()))
        .thenThrow(new CanvasConflictException(CanvasConflictException.Reason.VERSION_CONFLICT));
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCommandJson()))
        .andExpect(status().isConflict());

    when(commandService.applyCommands(any(UUID.class), anyLong(), any(UUID.class), anyList()))
        .thenThrow(
            new CanvasConflictException(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT));
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCommandJson()))
        .andExpect(status().isConflict());
  }

  @Test
  void idsAndVersionsRequireCanonicalForms() throws Exception {
    mockMvc.perform(get("/api/canvases/not-a-uuid")).andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCommandJson().replace(COMMAND.toString(), "cmd-1")))
        .andExpect(status().isBadRequest());
    verify(commandService, never())
        .applyCommands(any(UUID.class), anyLong(), any(UUID.class), anyList());
  }

  @Test
  void unknownFieldsAndUnknownCommandTypesAreRejectedByJackson() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":3,"commandId":"%s","extra":true,
                     "commands":[{"type":"DELETE_NODE","nodeId":"%s"}]}
                    """
                        .formatted(COMMAND, NODE_1)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":3,"commandId":"%s",
                     "commands":[{"type":"OLD_COMMAND"}]}
                    """
                        .formatted(COMMAND)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":3,"commandId":"%s",
                     "commands":[{"type":"DELETE_NODE","nodeId":"%s","legacy":true}]}
                    """
                        .formatted(COMMAND, NODE_1)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void changesEndpointIsNotExposed() throws Exception {
    // Canvas 跨窗口恢复只允许读取标准 Snapshot；旧 changes 路由必须彻底消失，避免兼容路径回流。
    mockMvc
        .perform(get("/api/canvases/" + CANVAS + "/changes?afterVersion=0"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteDeepDeletesCanvas() throws Exception {
    mockMvc
        .perform(delete("/api/canvases/" + CANVAS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("NO_CONTENT"));
    verify(commandService).deleteCanvas(CANVAS);
  }

  private String validCommandJson() {
    return """
        {"expectedVersion":"3","commandId":"%s",
         "commands":[{"type":"DELETE_NODE","nodeId":"%s"}]}
        """
        .formatted(COMMAND, NODE_1);
  }

  private JsonNode readTree(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsByteArray());
  }

  private static void assertTextual(JsonNode root, String pointer, String expected) {
    JsonNode node = root.at(pointer);
    assertEquals(true, node.isTextual(), pointer + " must be a JSON string");
    assertEquals(expected, node.textValue(), pointer);
  }

  private static void assertNullPresent(JsonNode root, String pointer) {
    JsonNode node = root.at(pointer);
    assertEquals(false, node.isMissingNode(), pointer + " must be present");
    assertEquals(true, node.isNull(), pointer + " must be JSON null");
  }

  private CanvasDocument document() {
    return new CanvasDocument(CANVAS, "demo", 3, NOW, NOW);
  }

  private CanvasSnapshot snapshot() {
    CanvasResource resource =
        new CanvasResource(RESOURCE_1, CANVAS, NODE_1, 0, BLOB_1, "a.png", null, NOW);
    CanvasResourceNode ordinary =
        new CanvasResourceNode(
            NODE_1,
            CANVAS,
            "note",
            new CanvasTransform(1, 2, 100, 80),
            GROUP,
            List.of(resource),
            null,
            null);
    CanvasResourceNode function =
        new CanvasResourceNode(
            NODE_2,
            CANVAS,
            "fn",
            new CanvasTransform(200, 2, 100, 80),
            null,
            List.of(),
            new CanvasFunction("model", "{}"),
            new CanvasFunctionRun(
                NODE_2,
                REQUEST,
                CanvasFunctionRunStatus.RUNNING,
                "QUEUED",
                "{\"stage\":\"QUEUED\",\"secret\":\"must-not-leak\"}",
                null,
                NOW));
    return new CanvasSnapshot(
        document(),
        List.of(ordinary, function),
        List.of(new CanvasGroup(GROUP, CANVAS, "group", new CanvasTransform(0, 0, 400, 200))),
        List.of(new CanvasLink(CANVAS, NODE_1, NODE_2)));
  }

  private CanvasPatch patch() {
    return new CanvasPatch(
        2L,
        3L,
        List.of(
            new CanvasGroupPatch.Upsert(
                new CanvasGroup(GROUP, CANVAS, "group", new CanvasTransform(0, 0, 400, 200)))),
        List.of(new CanvasNodePatch.Remove(NODE_2)),
        List.of(new CanvasLinkPatch.Upsert(new CanvasLink(CANVAS, NODE_1, NODE_2))));
  }
}
