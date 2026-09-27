package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasConflict;
import fun.fengwk.kkstudio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasReference;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.canvas.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasResourceInputDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasTransformDTO;
import fun.fengwk.kkstudio.share.canvas.CreateCanvasRequestDTO;
import fun.fengwk.kkstudio.web.mapper.WebDtoMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Canvas HTTP typed command、revision 回执、引用投影、严格参数校验与冲突映射。 */
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
  private StorageBlobManager blobManager;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    // 使用 convention4j 生产 ObjectMapper：long/Long 输出十进制字符串、默认 NON_NULL、FAIL_ON_UNKNOWN_PROPERTIES
    // 关闭（未知字段由 DTO @JsonAnySetter 拒绝），与真实 wire 一致。
    objectMapper = ObjectMapperHolder.getInstance();
    queryService = mock(CanvasQueryService.class);
    commandService = mock(CanvasCommandService.class);
    blobManager = mock(StorageBlobManager.class);
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
                    queryService, commandService, new WebDtoMapper(blobManager)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  /** 测试意图：snapshot 用 revision 表达同步位置，并把 Function args 投影成引用连线与服务端媒体事实。 */
  @Test
  void listAndSnapshotExposeRevisionReferencesAndBlobFacts() throws Exception {
    when(queryService.listDocuments()).thenReturn(List.of(document()));
    when(queryService.findSnapshot(CANVAS)).thenReturn(Optional.of(snapshot()));

    MvcResult list =
        mockMvc
            .perform(get("/api/canvases"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(CANVAS.toString()))
            .andExpect(jsonPath("$.data[0].revision").value("3"))
            .andExpect(jsonPath("$.data[0].version").doesNotExist())
            .andReturn();
    JsonNode listJson = readTree(list);
    assertEquals(
        true, listJson.at("/data/0/revision").isTextual(), "revision wire must be a JSON string");
    assertEquals("3", listJson.at("/data/0/revision").asText());

    MvcResult snapshotResult =
        mockMvc
            .perform(get("/api/canvases/" + CANVAS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.document.revision").value("3"))
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
            .andExpect(jsonPath("$.data.nodes[1].function.name").value("image.crop"))
            .andExpect(jsonPath("$.data.nodes[1].function.args.x").value(100))
            .andExpect(jsonPath("$.data.nodes[1].function.args.source.index").value(0))
            .andExpect(jsonPath("$.data.nodes[1].groupId").value(nullValue()))
            .andExpect(jsonPath("$.data.nodes[1].run.stage").value("QUEUED"))
            .andExpect(jsonPath("$.data.nodes[1].run.requestId").value(REQUEST.toString()))
            .andExpect(jsonPath("$.data.nodes[1].run.error").value(nullValue()))
            .andExpect(jsonPath("$.data.nodes[1].run.stateJson").doesNotExist())
            .andExpect(jsonPath("$.data.groups[0].id").value(GROUP.toString()))
            .andExpect(jsonPath("$.data.references[0].canvasId").value(CANVAS.toString()))
            .andExpect(jsonPath("$.data.references[0].sourceNodeId").value(NODE_1.toString()))
            .andExpect(jsonPath("$.data.references[0].targetNodeId").value(NODE_2.toString()))
            .andExpect(jsonPath("$.data.references[0].index").value(0))
            .andExpect(jsonPath("$.data.links").doesNotExist())
            .andReturn();
    JsonNode json = readTree(snapshotResult);
    assertTextual(json, "/data/document/revision", "3");
    assertTextual(json, "/data/nodes/0/resources/0/sizeBytes", "5");
    assertNullPresent(json, "/data/nodes/0/resources/0/durationMs");
    assertNullPresent(json, "/data/nodes/0/function");
    assertNullPresent(json, "/data/nodes/0/run");
    assertNullPresent(json, "/data/nodes/1/run/error");
  }

  /** 测试意图：Canvas 不拥有 Session，工作上下文属于 Harness，因此不注册任何 canvas session 归属入口。 */
  @Test
  void canvasHasNoSessionOwnershipEndpoint() throws Exception {
    mockMvc.perform(get("/api/canvases/" + CANVAS + "/sessions")).andExpect(status().isNotFound());
  }

  /** 测试意图：TEXT 资源没有媒体事实，创建画布必须显式提供非空白标题。 */
  @Test
  void textResourceHasNoBlobFactsAndCreateRequiresTitle() throws Exception {
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
        .andExpect(jsonPath("$.data.revision").value("3"))
        .andExpect(jsonPath("$.data.id").value(CANVAS.toString()));
    verify(commandService).createCanvas("board");

    mockMvc
        .perform(
            post("/api/canvases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(post("/api/canvases").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
    verify(commandService, never()).createCanvas(null);
  }

  /** 测试意图：11 种 typed command 直映射为领域命令，前置条件是各语义组的编辑起点旧值。 */
  @Test
  void typedCommandBatchMapsToDomainRecordsGroupByGroup() throws Exception {
    when(commandService.applyCommands(any(UUID.class), any(UUID.class), anyList()))
        .thenReturn(new CanvasCommandResult.Accepted(patch()));
    ApplyCanvasCommandsRequestDTO request = new ApplyCanvasCommandsRequestDTO();
    request.setIdempotencyKey(COMMAND.toString());
    request.setCommands(
        List.of(
            new CanvasCommandDTO.CreateNode(
                NODE_1.toString(),
                "note",
                new CanvasTransformDTO(1, 2, 100, 80),
                List.of(
                    new CanvasResourceInputDTO.Text("body", "hello"),
                    new CanvasResourceInputDTO.Blob("a.png", BLOB_1.toString()))),
            new CanvasCommandDTO.RenameNode(NODE_1.toString(), "note", "renamed"),
            new CanvasCommandDTO.SetNodeResources(
                NODE_1.toString(),
                List.of(RESOURCE_1.toString()),
                List.of(new CanvasResourceInputDTO.Keep(RESOURCE_1.toString()))),
            new CanvasCommandDTO.SetNodeFunction(NODE_1.toString(), null, functionDto()),
            new CanvasCommandDTO.SetNodeGroup(NODE_1.toString(), null, GROUP.toString()),
            new CanvasCommandDTO.UpdateNodeTransform(
                NODE_1.toString(), new CanvasTransformDTO(1, 2, 100, 80), null),
            new CanvasCommandDTO.CreateGroup(
                GROUP.toString(), "group", new CanvasTransformDTO(0, 0, 400, 200)),
            new CanvasCommandDTO.RenameGroup(GROUP.toString(), "group", "renamed group"),
            new CanvasCommandDTO.UpdateGroupTransform(
                GROUP.toString(), new CanvasTransformDTO(0, 0, 400, 200), null),
            new CanvasCommandDTO.DeleteGroup(GROUP.toString(), List.of(NODE_1.toString())),
            new CanvasCommandDTO.DeleteNode(NODE_2.toString(), List.of(), functionDto())));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value("3"))
        .andExpect(jsonPath("$.data.nodes[0].op").value("REMOVE"))
        .andExpect(jsonPath("$.data.nodes[0].nodeId").value(NODE_2.toString()))
        .andExpect(jsonPath("$.data.groups[0].op").value("UPSERT"))
        .andExpect(jsonPath("$.data.groups[0].group.id").value(GROUP.toString()));

    verify(commandService)
        .applyCommands(
            eq(CANVAS),
            eq(COMMAND),
            eq(
                List.of(
                    new CanvasCommand.CreateNode(
                        NODE_1,
                        "note",
                        new CanvasTransform(1, 2, 100, 80),
                        List.of(
                            new CanvasResourceInput.Text("body", "hello"),
                            new CanvasResourceInput.Blob("a.png", BLOB_1))),
                    new CanvasCommand.RenameNode(NODE_1, "note", "renamed"),
                    new CanvasCommand.SetNodeResources(
                        NODE_1,
                        List.of(RESOURCE_1),
                        List.of(new CanvasResourceInput.Keep(RESOURCE_1))),
                    new CanvasCommand.SetNodeFunction(NODE_1, null, cropFunction()),
                    new CanvasCommand.SetNodeGroup(NODE_1, null, GROUP),
                    new CanvasCommand.UpdateNodeTransform(
                        NODE_1, new CanvasTransform(1, 2, 100, 80), null),
                    new CanvasCommand.CreateGroup(
                        GROUP, "group", new CanvasTransform(0, 0, 400, 200)),
                    new CanvasCommand.RenameGroup(GROUP, "group", "renamed group"),
                    new CanvasCommand.UpdateGroupTransform(
                        GROUP, new CanvasTransform(0, 0, 400, 200), null),
                    new CanvasCommand.DeleteGroup(GROUP, List.of(NODE_1)),
                    new CanvasCommand.DeleteNode(NODE_2, List.of(), cropFunction()))));
  }

  /** 测试意图：冲突整批不写入，409 载荷给出受影响对象、语义组与服务端权威值。 */
  @Test
  void conflictsReturn409WithAffectedTargetsAndServerValues() throws Exception {
    when(commandService.applyCommands(any(UUID.class), any(UUID.class), anyList()))
        .thenReturn(
            new CanvasCommandResult.Conflicted(
                List.of(
                    new CanvasConflict.TargetMissing(NODE_2, CanvasConflict.Target.NODE),
                    new CanvasConflict.StaleNode(
                        NODE_1, CanvasConflict.NodeGroup.NAME, node(NODE_1, "server name", null)),
                    new CanvasConflict.NodeReferenced(NODE_2, List.of(NODE_1)),
                    new CanvasConflict.NodeRunning(NODE_2, run()))));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson("")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CANVAS_COMMAND_CONFLICT"))
        .andExpect(jsonPath("$.errors.conflicts[0].kind").value("TARGET_MISSING"))
        .andExpect(jsonPath("$.errors.conflicts[0].targetId").value(NODE_2.toString()))
        .andExpect(jsonPath("$.errors.conflicts[0].target").value("NODE"))
        .andExpect(jsonPath("$.errors.conflicts[1].kind").value("STALE_NODE"))
        .andExpect(jsonPath("$.errors.conflicts[1].group").value("NAME"))
        .andExpect(jsonPath("$.errors.conflicts[1].current.name").value("server name"))
        .andExpect(jsonPath("$.errors.conflicts[2].kind").value("NODE_REFERENCED"))
        .andExpect(jsonPath("$.errors.conflicts[2].referencingNodeIds[0]").value(NODE_1.toString()))
        .andExpect(jsonPath("$.errors.conflicts[3].kind").value("NODE_RUNNING"))
        .andExpect(jsonPath("$.errors.conflicts[3].run.status").value("RUNNING"));
  }

  /** 测试意图：画布不存在返回 404，同一幂等键绑定不同请求指纹返回 409。 */
  @Test
  void admissionFailuresMapToNotFoundAndIdempotencyConflict() throws Exception {
    when(commandService.applyCommands(any(UUID.class), any(UUID.class), anyList()))
        .thenThrow(
            new CanvasConflictException(
                CanvasConflictException.Reason.CANVAS_NOT_FOUND, "canvas document not found"));
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson("")))
        .andExpect(status().isNotFound());

    when(commandService.applyCommands(any(UUID.class), any(UUID.class), anyList()))
        .thenThrow(
            new CanvasConflictException(
                CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT, "different request"));
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson("")))
        .andExpect(status().isConflict());
  }

  /** 测试意图：精确重放只返回当时记录的接受位置与空变化集，不冒充新的执行状态。 */
  @Test
  void replayedAcceptanceReturnsRecordedRevisionOnly() throws Exception {
    when(commandService.applyCommands(any(UUID.class), any(UUID.class), anyList()))
        .thenReturn(new CanvasCommandResult.Accepted(CanvasPatch.receipt(2L)));

    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson("")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value("2"))
        .andExpect(jsonPath("$.data.nodes").isEmpty())
        .andExpect(jsonPath("$.data.groups").isEmpty())
        .andExpect(jsonPath("$.data.baseVersion").doesNotExist());
  }

  /** 测试意图：严格请求校验——未知字段、未知命令类型、非法 id、缺失或空命令批都在触达服务前被拒绝。 */
  @Test
  void strictRequestsAreRejectedBeforeAnyWrite() throws Exception {
    // 顶层未知字段。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson("\"extra\":true")))
        .andExpect(status().isBadRequest());
    // 已删除的 Link 写模型不再是合法命令类型，命令级未知字段同样拒绝。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"idempotencyKey":"%s","commands":[{"type":"CREATE_LINK"}]}
                    """
                        .formatted(COMMAND)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"idempotencyKey":"%s","commands":[{"type":"DELETE_NODE","nodeId":"%s",
                     "expectedResourceIds":[],"unexpected":true}]}
                    """
                        .formatted(COMMAND, NODE_1)))
        .andExpect(status().isBadRequest());
    // 嵌套资源槽位的未知判别值。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"idempotencyKey":"%s","commands":[{"type":"CREATE_NODE","nodeId":"%s",
                     "name":"n","transform":{"x":0,"y":0,"width":1,"height":1},
                     "resources":[{"kind":"UPLOAD","uploadId":"%s"}]}]}
                    """
                        .formatted(COMMAND, NODE_1, BLOB_1)))
        .andExpect(status().isBadRequest());
    // 非 canonical 的幂等键。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson("").replace(COMMAND.toString(), "cmd-1")))
        .andExpect(status().isBadRequest());
    // 缺失或空命令批，以及缺失请求体。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"%s\"}".formatted(COMMAND)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"%s\",\"commands\":[]}".formatted(COMMAND)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/canvases/not-a-uuid")).andExpect(status().isBadRequest());

    verify(commandService, never()).applyCommands(any(UUID.class), any(UUID.class), anyList());
  }

  /** 测试意图：Function args 是任意 JSON object，但仍受 Core 的 object、深度与长度上限约束。 */
  @Test
  void functionArgsAreStrictJsonObjectsWithinCoreLimits() throws Exception {
    // args 不是 object。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(setFunctionJson("5")))
        .andExpect(status().isBadRequest());
    // 超过 CanvasJson.MAX_DEPTH 的嵌套。
    StringBuilder nested = new StringBuilder();
    for (int depth = 0; depth <= CanvasJson.MAX_DEPTH + 1; depth++) {
      nested.append("{\"a\":");
    }
    nested.append('1');
    for (int depth = 0; depth <= CanvasJson.MAX_DEPTH + 1; depth++) {
      nested.append('}');
    }
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(setFunctionJson(nested.toString())))
        .andExpect(status().isBadRequest());
    // 超过 CanvasJson.MAX_LENGTH 的 args。
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    setFunctionJson("{\"text\":\"" + "x".repeat(CanvasJson.MAX_LENGTH) + "\"}")))
        .andExpect(status().isBadRequest());

    verify(commandService, never()).applyCommands(any(UUID.class), any(UUID.class), anyList());
  }

  @Test
  void deleteDeepDeletesCanvas() throws Exception {
    mockMvc
        .perform(delete("/api/canvases/" + CANVAS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("NO_CONTENT"));
    verify(commandService).deleteCanvas(CANVAS);
  }

  /** 一个最小合法命令批；{@code extraFields} 为附加的顶层成员片段（不含逗号）。 */
  private String commandJson(String extraFields) {
    String extra = extraFields.isEmpty() ? "" : "," + extraFields;
    return "{\"idempotencyKey\":\""
        + COMMAND
        + "\",\"commands\":[{\"type\":\"DELETE_NODE\",\"nodeId\":\""
        + NODE_1
        + "\",\"expectedResourceIds\":[]}]"
        + extra
        + "}";
  }

  private String setFunctionJson(String args) {
    return """
        {"idempotencyKey":"%s","commands":[{"type":"SET_NODE_FUNCTION","nodeId":"%s",
         "function":{"name":"image.crop","args":%s}}]}
        """
        .formatted(COMMAND, NODE_1, args);
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
    return new CanvasSnapshot(
        document(),
        List.of(node(NODE_1, "note", GROUP), functionNode()),
        List.of(new CanvasGroup(GROUP, CANVAS, "group", new CanvasTransform(0, 0, 400, 200))),
        List.of(new CanvasReference(CANVAS, NODE_1, NODE_2, 0)));
  }

  private CanvasResourceNode node(UUID nodeId, String name, UUID groupId) {
    return new CanvasResourceNode(
        nodeId,
        CANVAS,
        name,
        new CanvasTransform(1, 2, 100, 80),
        groupId,
        List.of(new CanvasResource(RESOURCE_1, CANVAS, nodeId, 0, BLOB_1, "a.png", null, NOW)),
        null,
        null);
  }

  private CanvasResourceNode functionNode() {
    return new CanvasResourceNode(
        NODE_2,
        CANVAS,
        "fn",
        new CanvasTransform(200, 2, 100, 80),
        null,
        List.of(),
        cropFunction(),
        run());
  }

  /** Function 节点当前运行；stateJson 是后端私有状态，绝不出现在 wire 上。 */
  private CanvasFunctionRun run() {
    return new CanvasFunctionRun(
        NODE_2,
        REQUEST,
        CanvasFunctionRunStatus.RUNNING,
        1,
        null,
        "lease",
        NOW.plusSeconds(30),
        "QUEUED",
        "{\"stage\":\"QUEUED\",\"secret\":\"must-not-leak\"}",
        null,
        NOW,
        NOW);
  }

  /** 引用基线以 {name,args} 表达；args 的 JSON 语义相等就是 Function 语义组相等。 */
  private static CanvasFunctionDTO functionDto() {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("type", "resource");
    source.put("nodeId", NODE_1.toString());
    source.put("index", 0);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("source", source);
    args.put("x", 100);
    return new CanvasFunctionDTO("image.crop", args);
  }

  private static CanvasFunction cropFunction() {
    return new CanvasFunction(
        "image.crop",
        CanvasJson.parseObject(
            """
            {"source":{"type":"resource","nodeId":"%s","index":0},"x":100}
            """
                .formatted(NODE_1)));
  }

  private CanvasPatch patch() {
    return new CanvasPatch(
        3L,
        List.of(new CanvasNodePatch.Remove(NODE_2)),
        List.of(
            new CanvasGroupPatch.Upsert(
                new CanvasGroup(GROUP, CANVAS, "group", new CanvasTransform(0, 0, 400, 200)))));
  }
}
