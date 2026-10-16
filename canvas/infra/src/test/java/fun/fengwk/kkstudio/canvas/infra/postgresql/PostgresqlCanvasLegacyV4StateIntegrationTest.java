package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult.Accepted;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionArgsCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionService;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownResolution;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionVersion;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRunTransactions;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionStartResult;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;
import fun.fengwk.kkstudio.canvas.infra.function.FixedCanvasFunctionCatalogSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 原样保留源 DEV/MAIN 版本 4 Canvas 历史的真实消费者边界（真实 PostgreSQL）。
 *
 * <p>本切片只验证一条候选边界：已终结 {@code SUCCEEDED}/{@code FAILED}/{@code CANCELLED} 的版本 4 state JSON 由现有
 * repository、query、run service 与 transactions 入口原样保留——查看与精确 requestId 重放返回同一份 JSON、同一
 * status/stage/requestId/error，既不执行旧计划，也不把缺失的目录 epoch/definition/source 伪造成 V5 绑定。UNKNOWN 仍只
 * 走人工核查：start/cancel 明确拒绝，resolve 必须先经 V5 decode 并显式失败。
 */
@Import(PostgresqlCanvasLegacyV4StateIntegrationTest.LegacyCatalogConfiguration.class)
class PostgresqlCanvasLegacyV4StateIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final CanvasTransform TRANSFORM = new CanvasTransform(10, 20, 300, 200);
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final String FIXTURE_DIR = "/fun/fengwk/kkstudio/canvas/infra/postgresql/";
  private static final ObjectMapper FIXTURE_MAPPER = JsonMapper.builder().build();
  private static final JsonObject EMPTY_ARGS_SCHEMA =
      CanvasJson.parseObject(
          "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}");
  private static final CanvasFunctionDefinition LEGACY_DEFINITION =
      CanvasFunctionDefinition.of(
          "legacy.video",
          "Legacy Video",
          EMPTY_ARGS_SCHEMA,
          CanvasResourceKind.VIDEO,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 0, Map.of()));

  /** manifest 与 pin 测试使用的历史输入 blob facts：与 fixture 内的精确数字保持一致。 */
  private static final String LEGACY_MEDIA_TYPE = "image/png";

  private static final long LEGACY_SIZE_BYTES = 10_485_760L;
  private static final long LEGACY_WIDTH = 3_840L;
  private static final long LEGACY_HEIGHT = 2_160L;

  /** 用于证明 V5 decode 不会被 legacy state 满足的显式拒绝消息。 */
  private static final String V5_VERSION_REJECTION = "state.version must be 5";

  @Autowired private CanvasQueryService queryService;
  @Autowired private CanvasCommandService commandService;
  @Autowired private CanvasFunctionService functionService;
  @Autowired private CanvasFunctionRunRepository runRepository;
  @Autowired private CanvasFunctionResourcePinRepository pinRepository;
  @Autowired private CanvasFunctionRunStateCodecPort stateCodec;
  @Autowired private CanvasFunctionArgsCodecPort argsCodec;
  @Autowired private CanvasResourceLifecycle resourceLifecycle;
  @Autowired private CanvasFunctionWorkStore workStore;
  @Autowired private FixedCanvasFunctionCatalogSource catalogSource;
  @Autowired private Clock clock;

  /** 真实 repository/query/run service 读取终态 legacy Run：status/stage/requestId/error 与整份 JSON 原样可见。 */
  @ParameterizedTest
  @EnumSource(
      value = CanvasFunctionRunStatus.class,
      names = {"SUCCEEDED", "FAILED", "CANCELLED"})
  void terminalV4StateIsReadVerbatimBySnapshotAndRunService(CanvasFunctionRunStatus status) {
    LegacyRun legacy = insertLegacyRun(status, true);

    assertFixtureStoredFaithfully(legacy);
    assertExactLegacyNumbers(legacy.nodeId());

    CanvasResourceNode node =
        nodeOf(queryService.findSnapshot(legacy.canvasId()).orElseThrow(), legacy.nodeId());
    CanvasFunctionRun snapshotRun = node.run();
    assertTerminalRunView(status, legacy, snapshotRun);

    CanvasFunctionRun fetched = functionService.get(legacy.canvasId(), legacy.nodeId());
    assertEquals(snapshotRun, fetched);
    assertVerbatimUnchanged(legacy);
  }

  /** 精确 requestId 重放即使 expectedVersion 过期、目录不含旧函数也返回原终态：零目录租用、零 pin/版本副作用。 */
  @ParameterizedTest
  @EnumSource(
      value = CanvasFunctionRunStatus.class,
      names = {"SUCCEEDED", "FAILED", "CANCELLED"})
  void exactRequestReplayReturnsTerminalV4WithoutCatalogLookup(CanvasFunctionRunStatus status) {
    LegacyRun legacy = insertLegacyRun(status, true);
    long revisionBefore = version(legacy.canvasId());
    int pinsBefore = pinCount(legacy.canvasId());
    CanvasFunctionVersion staleVersion = new CanvasFunctionVersion(99L, "0".repeat(64));

    // 目录条目 stub 拒绝任何 acquire：若重放没有在版本围栏与按名绑定之前返回，这里会直接抛错。
    RejectingCanvasFunctionCatalogSource rejecting = new RejectingCanvasFunctionCatalogSource();
    CanvasFunctionRunTransactions candidate = transactionsFor(rejecting);
    CanvasFunctionStartResult replay =
        transactions.execute(
            ignored ->
                candidate.start(
                    legacy.canvasId(),
                    legacy.nodeId(),
                    legacy.requestId().toString(),
                    staleVersion));

    assertFalse(replay.created());
    assertEquals(status, replay.run().status());
    assertEquals(legacy.requestId(), replay.run().requestId());
    assertEquals(legacy.storedJson(), replay.run().stateJson());
    assertEquals(0, rejecting.lookupCalls(), "exact replay must not look up the directory");
    assertEquals(revisionBefore, version(legacy.canvasId()));
    assertEquals(pinsBefore, pinCount(legacy.canvasId()));
    assertVerbatimUnchanged(legacy);

    // 同一路径经真实 Runtime service（宿主 FixedCanvasFunctionCatalogSource）也不取 current 租用。
    int appLookupsBefore = catalogSource.acquireCurrentCalls();
    CanvasFunctionRun serviceReplay =
        functionService.start(
            legacy.canvasId(), legacy.nodeId(), legacy.requestId().toString(), staleVersion);
    assertEquals(status, serviceReplay.status());
    assertEquals(legacy.storedJson(), serviceReplay.stateJson());
    assertEquals(
        appLookupsBefore,
        catalogSource.acquireCurrentCalls(),
        "runtime replay must not take a current lease");
    assertEquals(0, catalogSource.openLeases());
    assertVerbatimUnchanged(legacy);
  }

  /** worker 只 claim READY/RUNNING：终态 legacy 行永不出现在 claim 结果中，其它可调度 Run 不受影响。 */
  @Test
  void workerClaimDoesNotScheduleTerminalV4Rows() {
    LegacyRun succeeded = insertLegacyRun(CanvasFunctionRunStatus.SUCCEEDED, false);
    LegacyRun failed = insertLegacyRun(CanvasFunctionRunStatus.FAILED, false);
    LegacyRun cancelled = insertLegacyRun(CanvasFunctionRunStatus.CANCELLED, false);

    Instant idleNow = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    assertTrue(workStore.claimNext(idleNow, LEASE, "owner").isEmpty());

    UUID readyCanvas = UUID.randomUUID();
    UUID readyNode = UUID.randomUUID();
    canvasStore.addDocument(readyCanvas, "ready-canvas");
    canvasStore.addNode(functionNode(readyCanvas, readyNode, "ready-node"));
    CanvasFunctionRun started =
        functionService.start(
            readyCanvas, readyNode, UUID.randomUUID().toString(), currentVersion());

    ClaimedRun claim = workStore.claimNext(started.availableAt(), LEASE, "owner").orElseThrow();
    assertEquals(readyNode, claim.nodeId());
    assertEquals(started.requestId(), claim.requestId());

    for (LegacyRun legacy : List.of(succeeded, failed, cancelled)) {
      assertEquals(
          legacy.status(), runRepository.findByNodeId(legacy.nodeId()).orElseThrow().status());
      assertVerbatimUnchanged(legacy);
    }
  }

  /** 历史 Run 的 pin 保护遵循既有生命周期：换资源只解除挂接，删节点再释放 Run/pin 并回收孤儿资源。 */
  @Test
  void pinnedLegacyInputFollowsExistingResourceLifecycle() {
    LegacyRun legacy = insertLegacyRun(CanvasFunctionRunStatus.SUCCEEDED, true);
    assertEquals(1, pinCount(legacy.canvasId()));

    CanvasCommandResult replaced =
        commandService.applyCommands(
            legacy.canvasId(),
            UUID.randomUUID(),
            List.of(
                new CanvasCommand.SetNodeResources(
                    legacy.sourceNodeId(),
                    List.of(legacy.sourceResourceId()),
                    List.of(new CanvasResourceInput.Text("replacement.png", "replacement")))));
    assertInstanceOf(Accepted.class, replaced);

    CanvasResource detached =
        resourceRepository.findById(legacy.canvasId(), legacy.sourceResourceId()).orElseThrow();
    assertNull(
        detached.ownerNodeId(), "a pinned historical resource must be detached, not deleted");
    assertTrue(detached.isBlob());
    assertEquals(legacy.blobId(), detached.blobId());
    assertEquals(1, pinCount(legacy.canvasId()), "pin must survive the resource replacement");
    assertVerbatimUnchanged(legacy);

    CanvasCommandResult deleted =
        commandService.applyCommands(
            legacy.canvasId(),
            UUID.randomUUID(),
            List.of(
                new CanvasCommand.DeleteNode(legacy.nodeId(), List.of(), legacyCanvasFunction())));
    assertInstanceOf(Accepted.class, deleted);

    assertTrue(runRepository.findByNodeId(legacy.nodeId()).isEmpty());
    assertEquals(0, pinCount(legacy.canvasId()));
    assertTrue(
        resourceRepository.findById(legacy.canvasId(), legacy.sourceResourceId()).isEmpty(),
        "releasing the last pin must reclaim the orphaned historical resource");
  }

  /** 新 requestId 覆盖终态时由真实当前目录建立 V5 绑定，绝不把缺失 epoch/definition/source 的 V4 升级为假绑定。 */
  @Test
  void newRequestIdReplacesTerminalV4WithRealV5Binding() {
    LegacyRun legacy = insertLegacyRun(CanvasFunctionRunStatus.SUCCEEDED, true);
    long revisionBefore = version(legacy.canvasId());
    UUID replacement = UUID.randomUUID();

    CanvasFunctionRun started =
        functionService.start(
            legacy.canvasId(), legacy.nodeId(), replacement.toString(), currentVersion());

    assertEquals(CanvasFunctionRunStatus.READY, started.status());
    assertEquals("QUEUED", started.stage());
    assertEquals(replacement, started.requestId());
    CanvasFunctionFrozenRun frozen = stateCodec.decode(started.stateJson());
    assertEquals(1L, frozen.binding().catalogEpoch());
    assertNull(frozen.binding().pluginSource());
    assertEquals(
        CanvasFunctionCatalog.definitionFingerprint(LEGACY_DEFINITION),
        CanvasFunctionCatalog.definitionFingerprint(frozen.binding().definition()));
    assertEquals(revisionBefore + 1, version(legacy.canvasId()));
    assertEquals(0, pinCount(legacy.canvasId()), "the replaced legacy INPUT pin must be released");

    String stored = readStoredState(legacy.nodeId());
    assertNotEquals(legacy.storedJson(), stored);
    JsonNode storedPlan = FIXTURE_MAPPER.readTree(stored);
    assertEquals(5, storedPlan.get("version").intValue());
    assertEquals(replacement.toString(), storedPlan.get("plan").get("requestId").stringValue());
  }

  /** UNKNOWN 不是可保留只读终态：start/cancel 拒绝，resolve 必须先经 V5 decode 失败且不改 state/pins。 */
  @Test
  void unknownV4RowRejectsStartCancelAndResolveWithoutStateChange() {
    LegacyRun legacy = insertLegacyRun(CanvasFunctionRunStatus.UNKNOWN, true);
    long revisionBefore = version(legacy.canvasId());

    CanvasFunctionRunException startConflict =
        assertThrows(
            CanvasFunctionRunException.class,
            () ->
                functionService.start(
                    legacy.canvasId(),
                    legacy.nodeId(),
                    UUID.randomUUID().toString(),
                    currentVersion()));
    assertEquals(CanvasFunctionRunException.Reason.CONFLICT, startConflict.reason());

    CanvasFunctionRunException cancelConflict =
        assertThrows(
            CanvasFunctionRunException.class,
            () ->
                functionService.cancel(
                    legacy.canvasId(), legacy.nodeId(), legacy.requestId().toString()));
    assertEquals(CanvasFunctionRunException.Reason.CONFLICT, cancelConflict.reason());
    assertEquals(
        "UNKNOWN FunctionRun must be resolved, not cancelled", cancelConflict.getMessage());

    IllegalArgumentException decodeRejection =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                functionService.resolve(
                    legacy.canvasId(),
                    legacy.nodeId(),
                    legacy.requestId().toString(),
                    CanvasFunctionUnknownResolution.FAILED,
                    "verified absent"));
    assertEquals(V5_VERSION_REJECTION, decodeRejection.getMessage());

    CanvasFunctionRun unchanged = runRepository.findByNodeId(legacy.nodeId()).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.UNKNOWN, unchanged.status());
    assertEquals(legacy.storedJson(), unchanged.stateJson());
    assertEquals("legacy unknown", unchanged.error());
    assertEquals(revisionBefore, version(legacy.canvasId()));
    assertEquals(1, pinCount(legacy.canvasId()));
    assertVerbatimUnchanged(legacy);
  }

  /** 终态 cancel 直接返回原记录且不改写 state；CANCELLED 的 best-effort decode 问题在此显式暴露，不以静默异常为兼容证据。 */
  @ParameterizedTest
  @EnumSource(
      value = CanvasFunctionRunStatus.class,
      names = {"SUCCEEDED", "FAILED", "CANCELLED"})
  void cancelReturnsTerminalV4WithoutRewritingIt(CanvasFunctionRunStatus status) {
    LegacyRun legacy = insertLegacyRun(status, true);
    long revisionBefore = version(legacy.canvasId());

    CanvasFunctionRun cancelled =
        functionService.cancel(legacy.canvasId(), legacy.nodeId(), legacy.requestId().toString());

    assertEquals(status, cancelled.status());
    assertEquals(legacy.storedJson(), cancelled.stateJson());
    assertEquals(revisionBefore, version(legacy.canvasId()));
    assertEquals(1, pinCount(legacy.canvasId()));
    assertVerbatimUnchanged(legacy);

    if (status == CanvasFunctionRunStatus.CANCELLED) {
      // best-effort adapter cancel 会先 decode 冻结计划：legacy state 被 V5 明确拒绝，只是被该路径吞掉。
      IllegalArgumentException rejection =
          assertThrows(
              IllegalArgumentException.class, () -> stateCodec.decode(legacy.storedJson()));
      assertEquals(V5_VERSION_REJECTION, rejection.getMessage());
    }
  }

  private static void assertTerminalRunView(
      CanvasFunctionRunStatus status, LegacyRun legacy, CanvasFunctionRun run) {
    assertEquals(status, run.status());
    assertEquals(status.name(), run.stage());
    assertEquals(legacy.requestId(), run.requestId());
    assertEquals(legacy.error(), run.error());
    assertEquals(legacy.storedJson(), run.stateJson());
  }

  /** fixture 的完整结构（含 > 2^53 的精确整数）在写入 jsonb 后必须逐值保真。 */
  private void assertFixtureStoredFaithfully(LegacyRun legacy) {
    assertEquals(
        FIXTURE_MAPPER.readTree(legacy.fixtureJson()),
        FIXTURE_MAPPER.readTree(legacy.storedJson()));
  }

  /** 精确数字以数据库 JSON 路径逐项断言，覆盖 sizeBytes/width/height 与超过 double 精度的 adapterState 值。 */
  private void assertExactLegacyNumbers(UUID nodeId) {
    assertEquals("10485760", jsonText(nodeId, "{plan,manifest,0,sizeBytes}"));
    assertEquals("3840", jsonText(nodeId, "{plan,manifest,0,width}"));
    assertEquals("2160", jsonText(nodeId, "{plan,manifest,0,height}"));
    assertEquals("9007199254740993", jsonText(nodeId, "{adapterState,seed}"));
    assertEquals(
        Boolean.TRUE,
        jdbc.queryForObject(
            "select state_json #> '{plan,manifest,0,durationMs}' = 'null'::jsonb"
                + " from canvas_function_run where node_id = ?",
            Boolean.class,
            nodeId));
  }

  private void assertVerbatimUnchanged(LegacyRun legacy) {
    String stored = readStoredState(legacy.nodeId());
    assertEquals(legacy.storedJson(), stored, "legacy V4 state JSON must stay byte-identical");
    JsonNode tree = FIXTURE_MAPPER.readTree(stored);
    assertEquals(4, tree.get("version").intValue(), "legacy state must stay version 4");
    assertEquals(legacy.status().name(), tree.get("stage").stringValue());
  }

  private String jsonText(UUID nodeId, String path) {
    return jdbc.queryForObject(
        "select state_json #>> '" + path + "' from canvas_function_run where node_id = ?",
        String.class,
        nodeId);
  }

  private String readStoredState(UUID nodeId) {
    return jdbc.queryForObject(
        "select state_json::text from canvas_function_run where node_id = ?", String.class, nodeId);
  }

  private long version(UUID canvasId) {
    return canvasStore.findDocument(canvasId).orElseThrow().revision();
  }

  private int pinCount(UUID canvasId) {
    return pinRepository.findByCanvas(canvasId).size();
  }

  private CanvasFunctionRunTransactions transactionsFor(CanvasFunctionCatalogSource source) {
    return new CanvasFunctionRunTransactions(
        canvasStore,
        resourceRepository,
        runRepository,
        pinRepository,
        source,
        argsCodec,
        stateCodec,
        resourceLifecycle,
        UNUSED_BLOB_ACCESS,
        clock);
  }

  private CanvasFunctionVersion currentVersion() {
    try (CanvasFunctionCatalogSource.Lease lease = catalogSource.acquireCurrent()) {
      return new CanvasFunctionVersion(
          lease.epoch(), CanvasFunctionCatalog.definitionFingerprint(LEGACY_DEFINITION));
    }
  }

  private static NodeRecord functionNode(UUID canvasId, UUID nodeId, String name) {
    return new NodeRecord(
        nodeId,
        canvasId,
        name,
        TRANSFORM,
        null,
        new CanvasFunction(LEGACY_DEFINITION.name(), CanvasJson.parseObject("{}")));
  }

  private static CanvasFunction legacyCanvasFunction() {
    return new CanvasFunction(LEGACY_DEFINITION.name(), CanvasJson.parseObject("{}"));
  }

  private static CanvasResourceNode nodeOf(CanvasSnapshot snapshot, UUID nodeId) {
    return snapshot.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  /**
   * 按 fixture 的身份（canvas/node/request 与 manifest 事实）落库一条终态 legacy Run，可选地同时落历史输入资源与 INPUT pin。
   *
   * <p>历史 JSON 原样写入 {@code state_json}；画布、节点、资源与 pin 全部使用真实行，使后续断言走真实消费入口。
   */
  private LegacyRun insertLegacyRun(CanvasFunctionRunStatus status, boolean withSourceAndPin) {
    String fixtureJson = readFixture(status);
    JsonNode plan = FIXTURE_MAPPER.readTree(fixtureJson).get("plan");
    JsonNode reference = plan.get("manifest").get(0);
    UUID canvasId = UUID.fromString(plan.get("canvasId").stringValue());
    UUID nodeId = UUID.fromString(plan.get("nodeId").stringValue());
    UUID requestId = UUID.fromString(plan.get("requestId").stringValue());
    UUID sourceNodeId = UUID.fromString(reference.get("sourceNodeId").stringValue());
    UUID sourceResourceId = UUID.fromString(reference.get("resourceId").stringValue());
    UUID blobId = UUID.fromString(reference.get("blobId").stringValue());

    canvasStore.addDocument(canvasId, "legacy-canvas");
    if (withSourceAndPin) {
      canvasStore.addNode(
          new NodeRecord(sourceNodeId, canvasId, "legacy-source", TRANSFORM, null, null));
      addBlob(canvasId, sourceNodeId, sourceResourceId, blobId);
    }
    canvasStore.addNode(functionNode(canvasId, nodeId, "legacy-node"));
    jdbc.update(
        "insert into canvas_function_run"
            + " (node_id, request_id, status, attempt, state_json, error, created_at, updated_at)"
            + " values (?, ?, ?, 0, cast(? as jsonb), ?, current_timestamp, current_timestamp)",
        nodeId,
        requestId,
        status.name(),
        fixtureJson,
        legacyError(status));
    if (withSourceAndPin) {
      jdbc.update(
          "insert into canvas_function_resource_pin"
              + " (canvas_id, node_id, request_id, role, resource_id) values (?, ?, ?, 'INPUT', ?)",
          canvasId,
          nodeId,
          requestId,
          sourceResourceId);
    }
    return new LegacyRun(
        canvasId,
        nodeId,
        requestId,
        sourceNodeId,
        sourceResourceId,
        blobId,
        status,
        legacyError(status),
        fixtureJson,
        readStoredState(nodeId));
  }

  /** 历史输入 blob：storage_blob 与 canvas_resource 行都按 fixture 的精确尺寸写入。 */
  private void addBlob(UUID canvasId, UUID sourceNodeId, UUID sourceResourceId, UUID blobId) {
    jdbc.update(
        "insert into storage_blob"
            + " (id, sha256, size_bytes, media_type, width, height, ref_count, state)"
            + " values (?, ?, ?, ?, ?, ?, 1, 'ACTIVE')",
        blobId,
        String.format("%064x", blobId.getLeastSignificantBits() & Long.MAX_VALUE),
        LEGACY_SIZE_BYTES,
        LEGACY_MEDIA_TYPE,
        LEGACY_WIDTH,
        LEGACY_HEIGHT);
    resourceRepository.add(
        new CanvasResource(
            sourceResourceId,
            canvasId,
            sourceNodeId,
            0,
            blobId,
            "legacy-source.png",
            null,
            Instant.now()));
  }

  private static String legacyError(CanvasFunctionRunStatus status) {
    return switch (status) {
      case FAILED -> "legacy failure";
      case UNKNOWN -> "legacy unknown";
      default -> null;
    };
  }

  private static String readFixture(CanvasFunctionRunStatus status) {
    String path = FIXTURE_DIR + "legacy-v4-" + status.name().toLowerCase(Locale.ROOT) + ".json";
    try (InputStream stream =
        PostgresqlCanvasLegacyV4StateIntegrationTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("missing legacy V4 fixture: " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  /** 终态 legacy Run 的身份与原始 JSON。 */
  private record LegacyRun(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      UUID sourceNodeId,
      UUID sourceResourceId,
      UUID blobId,
      CanvasFunctionRunStatus status,
      String error,
      String fixtureJson,
      String storedJson) {}

  /** 目录 stub：任何 acquire 都直接抛错，用于证明精确重放完全不查目录。 */
  private static final class RejectingCanvasFunctionCatalogSource
      implements CanvasFunctionCatalogSource {

    private int lookupCalls;

    @Override
    public Lease acquireCurrent() {
      lookupCalls++;
      throw new IllegalStateException("exact replay must not acquire the current catalog");
    }

    @Override
    public Lease acquire(long catalogEpoch) {
      lookupCalls++;
      throw new IllegalStateException(
          "exact replay must not acquire catalog epoch " + catalogEpoch);
    }

    int lookupCalls() {
      return lookupCalls;
    }
  }

  /** 精确重放不触达 Blob 端口；命中即说明执行了不该执行的旧计划。 */
  private static final CanvasFunctionBlobAccess UNUSED_BLOB_ACCESS =
      new CanvasFunctionBlobAccess() {

        @Override
        public Optional<BlobFacts> findFacts(UUID blobId) {
          throw new UnsupportedOperationException("replay must not read blob facts");
        }

        @Override
        public CanvasFunctionResourceStream openOriginal(UUID blobId, long expectedSizeBytes) {
          throw new UnsupportedOperationException("replay must not open blob originals");
        }

        @Override
        public String originalUrl(UUID blobId, long expiresSeconds) {
          throw new UnsupportedOperationException("replay must not presign blob originals");
        }
      };

  /** 只为把 legacy.video 注册进测试目录：冻结目录仍由宿主 FixedCanvasFunctionCatalogSource 提供。 */
  @TestConfiguration(proxyBeanMethods = false)
  static class LegacyCatalogConfiguration {

    @Bean
    CanvasFunctionAdapter legacyFunctionAdapter() {
      return new CanvasFunctionAdapter() {

        @Override
        public List<CanvasFunctionDefinition> functions() {
          return List.of(LEGACY_DEFINITION);
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
        public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
          throw new UnsupportedOperationException("legacy state test does not submit");
        }

        @Override
        public List<UUID> execute(
            CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
          throw new UnsupportedOperationException("legacy state test does not execute");
        }
      };
    }
  }
}
