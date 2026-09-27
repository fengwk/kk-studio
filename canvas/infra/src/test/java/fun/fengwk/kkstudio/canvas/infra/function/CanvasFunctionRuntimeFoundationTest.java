package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionArgsCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownResolution;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresCanvasInfraTestSupport;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 真实 PostgreSQL 上验证 Runtime 的锁、两阶段提交、UNKNOWN 收敛、人工解除、CAS、pin 与资源生命周期不变量。 */
@Import(CanvasFunctionRuntimeFoundationTest.FoundationConfiguration.class)
class CanvasFunctionRuntimeFoundationTest extends PostgresCanvasInfraTestSupport {

  private static final String REQUEST_1 = "00000000-0000-0000-0000-000000000101";
  private static final String REQUEST_2 = "00000000-0000-0000-0000-000000000102";
  private static final CanvasTransform TRANSFORM = new CanvasTransform(0, 0, 100, 80);
  private static final JsonObject ARGS_SCHEMA =
      CanvasJson.parseObject(
          """
          {
            "type": "object",
            "additionalProperties": false,
            "required": ["prompt"],
            "properties": {
              "prompt": { "type": "string" },
              "ratio": { "type": "string", "enum": ["16:9", "1:1"], "default": "16:9" },
              "source": { "type": "resourceReference" }
            }
          }
          """);
  private static final CanvasFunctionDefinition DEFINITION =
      CanvasFunctionDefinition.of(
          "test.image",
          "Test Image",
          ARGS_SCHEMA,
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  private static final CanvasFunctionDefinition MULTI_MEDIA_DEFINITION =
      new CanvasFunctionDefinition(
          "test.multimedia",
          "Test Multimedia",
          ARGS_SCHEMA,
          List.of(
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "preview.png"),
              CanvasFunctionOutputSpec.named(CanvasResourceKind.VIDEO, "video.mp4")),
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  private static final CanvasFunctionDefinition TEXT_IMAGE_DEFINITION =
      new CanvasFunctionDefinition(
          "test.textimage",
          "Test Text and Image",
          ARGS_SCHEMA,
          List.of(
              CanvasFunctionOutputSpec.named(CanvasResourceKind.TEXT, "summary.txt"),
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "preview.png")),
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  private static final CanvasFunctionDefinition TWO_IMAGES_DEFINITION =
      new CanvasFunctionDefinition(
          "test.twoimages",
          "Test Two Images",
          ARGS_SCHEMA,
          List.of(
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "first.png"),
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "second.png")),
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));

  @Autowired private CanvasResourceRepository resourceRepository;
  @Autowired private CanvasFunctionRunRepository runRepository;
  @Autowired private CanvasFunctionResourcePinRepository pinRepository;
  @Autowired private CanvasFunctionArgsCodecPort argsCodec;
  @Autowired private Clock clock;
  private CanvasFunctionRunTransactions runtimeTransactions;

  /** Runtime foundation 使用模块内的忠实 pin/owner 双替身，避免与实际 Blob ref_count 端口耦合。 */
  @BeforeEach
  void buildRuntimeTransactions() {
    runtimeTransactions =
        new CanvasFunctionRunTransactions(
            canvasStore,
            resourceRepository,
            runRepository,
            pinRepository,
            catalog,
            argsCodec,
            stateCodec,
            new TestResourceLifecycle(resourceRepository, pinRepository),
            blobAccess,
            clock);
  }

  @Autowired private CanvasFunctionCatalog catalog;
  @Autowired private CanvasFunctionRunStateCodecPort stateCodec;
  @Autowired private CanvasFunctionWorkStore workStore;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TestBlobAccess blobAccess;

  /** start 必须按 document/node 行串行并保持 requestId 幂等；只有真实状态前进才增加 document version。 */
  @Test
  void serializesStartAndKeepsRequestIdIdempotent() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());

    CanvasFunctionRun first = runtimeTransactions.start(canvasId, nodeId, REQUEST_1).run();
    assertEquals(CanvasFunctionRunStatus.READY, first.status());
    assertEquals(1L, version(canvasId));
    // 无引用的 Run 在启动阶段不产生 pin：预分配目标没有 Resource 行，OUTPUT pin 属于物化事务。
    assertTrue(pinRepository.findByRun(canvasId, nodeId, first.requestId()).isEmpty());

    CanvasFunctionStartResult replay = runtimeTransactions.start(canvasId, nodeId, REQUEST_1);
    assertFalse(replay.created());
    assertEquals(first.requestId(), replay.run().requestId());
    assertEquals(first.status(), replay.run().status());
    assertEquals(decode(first), decode(replay.run()));
    assertEquals(1L, version(canvasId), "exact replay must not bump version");
    assertThrows(
        CanvasFunctionRunException.class,
        () -> runtimeTransactions.start(canvasId, nodeId, REQUEST_2));

    CanvasFunctionFrozenRun firstFrozen = decode(first);
    CanvasResource cancelledTarget =
        materializeTarget(canvasId, nodeId, first.requestId(), firstFrozen.output(0));
    runtimeTransactions.cancel(canvasId, nodeId, REQUEST_1);
    CanvasFunctionRun cancelledReplay = runtimeTransactions.cancel(canvasId, nodeId, REQUEST_1);
    assertEquals(CanvasFunctionRunStatus.CANCELLED, cancelledReplay.status());
    assertTrue(pinRepository.findByRun(canvasId, nodeId, first.requestId()).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, cancelledTarget.id()).isEmpty());
    assertEquals(2L, version(canvasId), "terminal cancel replay must not bump version");
    CanvasFunctionRun replacement = runtimeTransactions.start(canvasId, nodeId, REQUEST_2).run();
    assertNotEquals(first.requestId(), replacement.requestId());
    assertEquals(3L, version(canvasId));
  }

  /** 冻结 manifest 必须来自同画布已连线 Resource 的 Blob facts；成功终态原子挂接预分配目标。 */
  @Test
  void freezesReferenceFactsAndAttachesOnlyThePreallocatedTarget() {
    UUID canvasId = addDocument();
    UUID sourceNodeId = addPlainNode(canvasId, "source");
    UUID targetNodeId = addFunctionNode(canvasId, "target", configWithReference(sourceNodeId));
    CanvasResource source = addBlobResource(canvasId, sourceNodeId, 0, UUID.randomUUID());

    ClaimedRun claim = claimStartedRun(canvasId, targetNodeId, REQUEST_1, "foundation-success");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    assertEquals(source.blobId(), frozen.manifest().get(0).blobId());
    assertEquals(3L, frozen.manifest().get(0).sizeBytes());

    CanvasResource target =
        materializeTarget(canvasId, targetNodeId, frozen.requestId(), frozen.output(0));
    assertTrue(
        runtimeTransactions.completeSuccess(
            frozen, claim.leaseToken(), frozen.outputResourceIds()));

    CanvasResource attached = resourceRepository.findById(canvasId, target.id()).orElseThrow();
    assertEquals(targetNodeId, attached.ownerNodeId());
    assertEquals(0, attached.resourceIndex());
    assertEquals(
        CanvasFunctionRunStatus.SUCCEEDED,
        runRepository.findByNodeId(targetNodeId).orElseThrow().status());
    assertTrue(pinRepository.findByRun(canvasId, targetNodeId, frozen.requestId()).isEmpty());
    assertEquals(2L, version(canvasId), "start and terminal swap each bump once");
  }

  /** 验收证据 1：提交意图在外部提交前持久化（SUBMITTING -> SUBMITTED -> SUCCEEDED），成功后原子挂接唯一资源并释放全部 pin。 */
  @Test
  void submitIntentPersistedBeforeExternalSubmitAndHappyPathReleasesPins() {
    UUID canvasId = addDocument();
    UUID sourceNodeId = addPlainNode(canvasId, "source");
    UUID targetNodeId = addFunctionNode(canvasId, "target", configWithReference(sourceNodeId));
    CanvasResource source = addBlobResource(canvasId, sourceNodeId, 0, UUID.randomUUID());

    // 1. start 构造 PENDING 状态并落库 INPUT pin
    CanvasFunctionRun ready = runtimeTransactions.start(canvasId, targetNodeId, REQUEST_1).run();
    assertEquals(CanvasFunctionRunStatus.READY, ready.status());
    CanvasFunctionFrozenRun frozenReady = decode(ready);
    assertEquals(CanvasFunctionSubmitState.PENDING, frozenReady.submitState());
    assertEquals("QUEUED", frozenReady.stage());
    assertEquals(1, pinRepository.findByRun(canvasId, targetNodeId, ready.requestId()).size());

    // 2. Worker 认领 Run
    ClaimedRun claim =
        workStore.claimNext(ready.availableAt(), Duration.ofSeconds(30), "worker-1").orElseThrow();
    CanvasFunctionFrozenRun frozen = decode(claim.run());

    // 3. beginSubmit 持久化提交意图 (SUBMITTING)
    CanvasFunctionFrozenRun submitting =
        runtimeTransactions.beginSubmit(frozen, claim.leaseToken());
    assertEquals(CanvasFunctionSubmitState.SUBMITTING, submitting.submitState());
    assertEquals("SUBMITTING", submitting.stage());
    CanvasFunctionRun runningInDb = runRepository.findByNodeId(targetNodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, runningInDb.status());
    assertEquals(CanvasFunctionSubmitState.SUBMITTING, decode(runningInDb).submitState());

    // 4. confirmSubmitted 持久化提交确认 (SUBMITTED)
    CanvasFunctionFrozenRun submitted =
        runtimeTransactions.confirmSubmitted(submitting, claim.leaseToken());
    assertEquals(CanvasFunctionSubmitState.SUBMITTED, submitted.submitState());
    assertEquals("SUBMITTED", submitted.stage());
    assertTrue(submitted.submitted());

    // 5. 宿主物化目标资源 (写入 OUTPUT pin 与未挂接 Resource 行)
    CanvasResource target =
        materializeTarget(canvasId, targetNodeId, submitted.requestId(), submitted.output(0));
    assertEquals(2, pinRepository.findByRun(canvasId, targetNodeId, submitted.requestId()).size());

    // 6. completeSuccess 原子收敛为 SUCCEEDED 并释放 pin
    assertTrue(
        runtimeTransactions.completeSuccess(
            submitted, claim.leaseToken(), submitted.outputResourceIds()));

    CanvasFunctionRun completed = runRepository.findByNodeId(targetNodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.SUCCEEDED, completed.status());
    assertEquals("SUCCEEDED", completed.stage());
    assertNull(completed.error());

    CanvasResource attached = resourceRepository.findById(canvasId, target.id()).orElseThrow();
    assertEquals(targetNodeId, attached.ownerNodeId());
    assertEquals(0, attached.resourceIndex());
    assertTrue(
        pinRepository.findByRun(canvasId, targetNodeId, submitted.requestId()).isEmpty(),
        "both INPUT and OUTPUT pins must be released upon success");
  }

  /** 验收证据 2：SUBMITTING 崩溃恢复后收敛为 UNKNOWN 且保留 pin 与目标；resolve(RESUME) 回到 READY 且不再重新提交。 */
  @Test
  void resumedSubmittingRunConvergesToUnknownAndResumingAllowsQueryExecution() {
    UUID canvasId = addDocument();
    UUID sourceNodeId = addPlainNode(canvasId, "source");
    UUID targetNodeId = addFunctionNode(canvasId, "target", configWithReference(sourceNodeId));
    addBlobResource(canvasId, sourceNodeId, 0, UUID.randomUUID());

    ClaimedRun claim = claimStartedRun(canvasId, targetNodeId, REQUEST_1, "worker-submitting");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    CanvasResource target =
        materializeTarget(canvasId, targetNodeId, frozen.requestId(), frozen.output(0));

    // 推进到 SUBMITTING
    runtimeTransactions.beginSubmit(frozen, claim.leaseToken());

    // 模拟提交结果不明，收敛为 UNKNOWN
    assertTrue(
        runtimeTransactions.markUnknown(
            targetNodeId,
            REQUEST_1,
            claim.leaseToken(),
            "external submission outcome is unknown; manual verification required"));

    CanvasFunctionRun unknownRun = runRepository.findByNodeId(targetNodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.UNKNOWN, unknownRun.status());
    assertEquals("UNKNOWN", unknownRun.stage());
    assertEquals(
        "external submission outcome is unknown; manual verification required", unknownRun.error());
    assertNull(unknownRun.leaseToken());

    // UNKNOWN 状态下必须保留 INPUT 和 OUTPUT pin，预分配目标资源不能被丢弃
    assertFalse(pinRepository.findByRun(canvasId, targetNodeId, unknownRun.requestId()).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, target.id()).isPresent());

    // UNKNOWN 状态下拒绝不同 requestId 的 start，也拒绝直接 cancel
    assertThrows(
        CanvasFunctionRunException.class,
        () -> runtimeTransactions.start(canvasId, targetNodeId, REQUEST_2));
    assertThrows(
        CanvasFunctionRunException.class,
        () -> runtimeTransactions.cancel(canvasId, targetNodeId, REQUEST_1));

    // resolve RESUME 必须要求非空 verification，并使 Run 回到 READY，submitState 推进为 SUBMITTED
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.resolve(
                canvasId, targetNodeId, REQUEST_1, CanvasFunctionUnknownResolution.RESUME, ""));

    CanvasFunctionRun resumed =
        runtimeTransactions.resolve(
            canvasId,
            targetNodeId,
            REQUEST_1,
            CanvasFunctionUnknownResolution.RESUME,
            "manual verification: external job exists with id job-123");
    assertEquals(CanvasFunctionRunStatus.READY, resumed.status());
    assertEquals("SUBMITTED", resumed.stage());
    assertNull(resumed.error());
    CanvasFunctionFrozenRun frozenResumed = decode(resumed);
    assertEquals(CanvasFunctionSubmitState.SUBMITTED, frozenResumed.submitState());
    assertTrue(frozenResumed.submitted());

    // pin 仍然保留
    assertFalse(pinRepository.findByRun(canvasId, targetNodeId, resumed.requestId()).isEmpty());

    // 再次领取执行：已处于 SUBMITTED，直接 execute 物化并 completeSuccess
    ClaimedRun resumedClaim =
        workStore
            .claimNext(resumed.availableAt(), Duration.ofSeconds(30), "worker-resumed")
            .orElseThrow();
    assertTrue(
        runtimeTransactions.completeSuccess(
            decode(resumedClaim.run()),
            resumedClaim.leaseToken(),
            frozenResumed.outputResourceIds()));

    CanvasFunctionRun finalRun = runRepository.findByNodeId(targetNodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.SUCCEEDED, finalRun.status());
    assertTrue(pinRepository.findByRun(canvasId, targetNodeId, resumed.requestId()).isEmpty());
  }

  /** 验收证据 3：UNKNOWN 状态 resolve 为 FAILED/CANCELLED：终态落库、释放 pin、丢弃未挂接目标并持久化核查文本；重复 resolve 冲突。 */
  @Test
  void resolveUnknownTerminalReleasesPinsAndDiscardsUnownedTarget() {
    UUID canvasId = addDocument();
    UUID sourceNodeId = addPlainNode(canvasId, "source");
    UUID targetNodeId = addFunctionNode(canvasId, "target", configWithReference(sourceNodeId));
    addBlobResource(canvasId, sourceNodeId, 0, UUID.randomUUID());

    // 1. 测试 UNKNOWN -> FAILED
    ClaimedRun claim1 = claimStartedRun(canvasId, targetNodeId, REQUEST_1, "worker-failed");
    CanvasFunctionFrozenRun frozen1 = decode(claim1.run());
    CanvasResource target1 =
        materializeTarget(canvasId, targetNodeId, frozen1.requestId(), frozen1.output(0));
    runtimeTransactions.beginSubmit(frozen1, claim1.leaseToken());
    runtimeTransactions.markUnknown(targetNodeId, REQUEST_1, claim1.leaseToken(), "timeout");

    CanvasFunctionRun failedResolved =
        runtimeTransactions.resolve(
            canvasId,
            targetNodeId,
            REQUEST_1,
            CanvasFunctionUnknownResolution.FAILED,
            "external task confirmed not created");
    assertEquals(CanvasFunctionRunStatus.FAILED, failedResolved.status());
    assertEquals("FAILED", failedResolved.stage());
    assertEquals("external task confirmed not created", failedResolved.error());

    // pin 被释放，未挂接目标被丢弃
    assertTrue(
        pinRepository.findByRun(canvasId, targetNodeId, UUID.fromString(REQUEST_1)).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, target1.id()).isEmpty());

    // 再次 resolve 产生冲突
    assertThrows(
        CanvasFunctionRunException.class,
        () ->
            runtimeTransactions.resolve(
                canvasId,
                targetNodeId,
                REQUEST_1,
                CanvasFunctionUnknownResolution.FAILED,
                "again"));

    // 2. 测试 UNKNOWN -> CANCELLED
    CanvasFunctionRun replacement =
        runtimeTransactions.start(canvasId, targetNodeId, REQUEST_2).run();
    ClaimedRun claim2 =
        workStore
            .claimNext(replacement.availableAt(), Duration.ofSeconds(30), "worker-cancelled")
            .orElseThrow();
    CanvasFunctionFrozenRun frozen2 = decode(claim2.run());
    CanvasResource target2 =
        materializeTarget(canvasId, targetNodeId, frozen2.requestId(), frozen2.output(0));
    runtimeTransactions.beginSubmit(frozen2, claim2.leaseToken());
    runtimeTransactions.markUnknown(targetNodeId, REQUEST_2, claim2.leaseToken(), "timeout-2");

    CanvasFunctionRun cancelledResolved =
        runtimeTransactions.resolve(
            canvasId,
            targetNodeId,
            REQUEST_2,
            CanvasFunctionUnknownResolution.CANCELLED,
            "external task cancelled by operator");
    assertEquals(CanvasFunctionRunStatus.CANCELLED, cancelledResolved.status());
    assertEquals("CANCELLED", cancelledResolved.stage());
    assertEquals("external task cancelled by operator", cancelledResolved.error());

    assertTrue(
        pinRepository.findByRun(canvasId, targetNodeId, UUID.fromString(REQUEST_2)).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, target2.id()).isEmpty());

    assertThrows(
        CanvasFunctionRunException.class,
        () ->
            runtimeTransactions.resolve(
                canvasId,
                targetNodeId,
                REQUEST_2,
                CanvasFunctionUnknownResolution.CANCELLED,
                "again"));
  }

  /** 验收证据 4：结果列表不匹配（空或多元素）直接失败且不发布目标；过期租约写回被拒绝。 */
  @Test
  void completeSuccessRejectsMismatchAndStaleTokens() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "worker-fencing");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    CanvasResource target =
        materializeTarget(canvasId, nodeId, frozen.requestId(), frozen.output(0));

    // 结果列表为空
    assertThrows(
        IllegalArgumentException.class,
        () -> runtimeTransactions.completeSuccess(frozen, claim.leaseToken(), List.of()));

    // 结果列表包含多余元素
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen,
                claim.leaseToken(),
                List.of(frozen.output(0).resourceId(), UUID.randomUUID())));

    // 结果列表 ID 不匹配预分配目标
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), List.of(UUID.randomUUID())));

    // 失效租约写回返回 false，状态保持 RUNNING
    assertFalse(
        runtimeTransactions.completeSuccess(
            frozen, "stale-lease-token", frozen.outputResourceIds()));
    assertFalse(
        runtimeTransactions.markUnknown(
            nodeId, REQUEST_1, "stale-lease-token", "stale unknown reason"));

    CanvasFunctionRun runInDb = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, runInDb.status());
    assertEquals(claim.leaseToken(), runInDb.leaseToken());

    // target 仍然保持未挂接
    CanvasResource unowned = resourceRepository.findById(canvasId, target.id()).orElseThrow();
    assertNull(unowned.ownerNodeId());
  }

  /** 验收证据 5：args Schema 校验在 start 阶段拦截未知字段、类型错误与缺失必填，合法输入补齐默认值并冻结到计划中。 */
  @Test
  void argsValidationRejectsMalformedAndFillsDefaultsAtStart() {
    UUID canvasId = addDocument();

    // 未知字段 extra
    UUID nodeUnknown = addFunctionNode(canvasId, "node1", "{\"prompt\":\"test\",\"extra\":1}");
    assertThrows(
        IllegalArgumentException.class,
        () -> runtimeTransactions.start(canvasId, nodeUnknown, REQUEST_1));

    // 缺少必填字段 prompt
    UUID nodeMissing = addFunctionNode(canvasId, "node2", "{\"ratio\":\"16:9\"}");
    assertThrows(
        IllegalArgumentException.class,
        () -> runtimeTransactions.start(canvasId, nodeMissing, REQUEST_1));

    // 类型错误：prompt 应为 string
    UUID nodeWrongType = addFunctionNode(canvasId, "node3", "{\"prompt\":123}");
    assertThrows(
        IllegalArgumentException.class,
        () -> runtimeTransactions.start(canvasId, nodeWrongType, REQUEST_1));

    // 合法输入：只传必填 prompt，默认值 ratio="16:9" 必须被自动补齐并冻结
    UUID nodeValid = addFunctionNode(canvasId, "node4", "{\"prompt\":\"hello world\"}");
    CanvasFunctionRun run = runtimeTransactions.start(canvasId, nodeValid, REQUEST_1).run();
    CanvasFunctionFrozenRun frozen = decode(run);
    assertEquals(new CanvasJson.JsonText("hello world"), frozen.args().values().get("prompt"));
    assertEquals(new CanvasJson.JsonText("16:9"), frozen.args().values().get("ratio"));
  }

  /** 外层事务回滚必须同时撤销 checkpoint state 与 document version，不能留下部分提交。 */
  @Test
  void checkpointRollbackLeavesRunAndVersionUntouched() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "foundation-rollback");
    long versionAfterStart = version(canvasId);

    TransactionTemplate template = new TransactionTemplate(transactionManager);
    assertThrows(
        IllegalStateException.class,
        () ->
            template.executeWithoutResult(
                status -> {
                  runtimeTransactions.checkpoint(
                      canvasId,
                      nodeId,
                      REQUEST_1,
                      claim.leaseToken(),
                      "SUBMITTED",
                      Map.of("jobId", "job"));
                  throw new IllegalStateException("rollback");
                }));

    assertEquals(versionAfterStart, version(canvasId));
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals("QUEUED", current.stage());
    assertFalse(current.stateJson().contains("jobId"));
  }

  /** checkpoint/failure 必须由当前 lease token fencing；失效 token 只产生内部取消或 no-op。 */
  @Test
  void checkpointAndFailureRequireTheCurrentLease() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "foundation-fencing");

    runtimeTransactions.checkpoint(
        canvasId, nodeId, REQUEST_1, claim.leaseToken(), "SUBMITTED", Map.of("jobId", "job"));
    assertEquals(2L, version(canvasId));
    assertEquals("SUBMITTED", runRepository.findByNodeId(nodeId).orElseThrow().stage());
    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () ->
            runtimeTransactions.checkpoint(canvasId, nodeId, REQUEST_1, "stale", "LATE", Map.of()));
    assertFalse(runtimeTransactions.failIfRunning(nodeId, REQUEST_1, "stale", "ignored failure"));
    assertTrue(
        runtimeTransactions.failIfRunning(nodeId, REQUEST_1, claim.leaseToken(), "safe failure"));
    CanvasFunctionRun failed = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.FAILED, failed.status());
    assertEquals("safe failure", failed.error());
    assertTrue(pinRepository.findByRun(canvasId, nodeId, failed.requestId()).isEmpty());
    assertEquals(3L, version(canvasId));
  }

  /** completeSuccess 的失效 token 必须在读取/交换 target 前被拒绝，不能污染可见资源。 */
  @Test
  void completeSuccessWithStaleLeaseIsANoOp() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "foundation-success-fence");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    CanvasResource target =
        materializeTarget(canvasId, nodeId, frozen.requestId(), frozen.output(0));

    assertFalse(runtimeTransactions.completeSuccess(frozen, "stale", frozen.outputResourceIds()));
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, current.status());
    assertEquals(claim.leaseToken(), current.leaseToken());
    assertTrue(resourceRepository.findById(canvasId, target.id()).isPresent());
    assertEquals(1L, version(canvasId));
  }

  /** 节点不再是 Function 后，迟到的成功与失败写回都必须保持 no-op。 */
  @Test
  void terminalWritebacksStopAfterNodeIsNoLongerAFunction() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "foundation-node-fence");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    jdbc.update("update canvas_node set \"function\" = null where id = ?", nodeId);

    assertFalse(
        runtimeTransactions.completeSuccess(
            frozen, claim.leaseToken(), frozen.outputResourceIds()));
    assertFalse(
        runtimeTransactions.failIfRunning(
            nodeId, REQUEST_1, claim.leaseToken(), "ignored failure"));
    assertEquals(
        CanvasFunctionRunStatus.RUNNING, runRepository.findByNodeId(nodeId).orElseThrow().status());
    assertEquals(1L, version(canvasId));
  }

  /** success 必须拒绝错误结果列表与尚未物化的 target，并保持 RUNNING/版本不变。 */
  @Test
  void completeSuccessRejectsInvalidOrUnmaterializedTarget() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "foundation-invalid-success");
    CanvasFunctionFrozenRun frozen = decode(claim.run());

    assertThrows(
        IllegalArgumentException.class,
        () -> runtimeTransactions.completeSuccess(frozen, claim.leaseToken(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), frozen.outputResourceIds()));
    CanvasResource target =
        materializeTarget(canvasId, nodeId, frozen.requestId(), frozen.output(0));
    blobAccess.put(
        new CanvasFunctionBlobAccess.BlobFacts(
            target.blobId(), "video/mp4", 3L, null, null, 1_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), frozen.outputResourceIds()));
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, current.status());
    assertEquals(1L, version(canvasId));
  }

  /** checkpoint 在 document 或 Function node 消失后必须内部取消，绝不重建已删除聚合。 */
  @Test
  void checkpointCancelsAfterAggregateDisappears() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "foundation-delete-fence");
    jdbc.update("delete from canvas_function_resource_pin where node_id = ?", nodeId);
    jdbc.update("delete from canvas_function_run where node_id = ?", nodeId);
    jdbc.update("delete from canvas_node where id = ?", nodeId);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () ->
            runtimeTransactions.checkpoint(
                canvasId, nodeId, REQUEST_1, claim.leaseToken(), "LATE", Map.of()));
    jdbc.update("delete from canvas_document where id = ?", canvasId);
    assertThrows(
        CanvasFunctionRunException.class,
        () ->
            runtimeTransactions.checkpoint(
                canvasId, nodeId, REQUEST_1, claim.leaseToken(), "LATE", Map.of()));
  }

  /**
   * 验收测试 1 - 双媒体输出原子发布： 声明 2 个媒体槽位的函数在单个 SUCCEEDED 事务中同时发布两个资源， 节点最终严格按计划槽位顺序挂接 index 0 和 1 的资源，Run
   * 收敛为 SUCCEEDED，且本次 Run 的 pin 全部释放。
   */
  @Test
  void multiOutputTwoMediaSlotsPublishAtomicallyInPlanOrderAndReleasePins() {
    UUID canvasId = addDocument();
    UUID nodeId =
        addFunctionNode(
            canvasId, "multi-media-node", MULTI_MEDIA_DEFINITION.name(), configWithoutReferences());

    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "worker-two-media");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    assertEquals(2, frozen.outputs().size());
    CanvasFunctionFrozenOutput slot0 = frozen.output(0);
    CanvasFunctionFrozenOutput slot1 = frozen.output(1);
    assertEquals(CanvasResourceKind.IMAGE, slot0.kind());
    assertEquals("preview.png", slot0.name());
    assertEquals(CanvasResourceKind.VIDEO, slot1.kind());
    assertEquals("video.mp4", slot1.name());

    // 物化两个槽位
    CanvasResource res0 = materializeBlobOutput(canvasId, nodeId, frozen.requestId(), slot0);
    CanvasResource res1 = materializeBlobOutput(canvasId, nodeId, frozen.requestId(), slot1);
    assertEquals(2, pinRepository.findByRun(canvasId, nodeId, frozen.requestId()).size());

    // 单事务原子发布
    assertTrue(
        runtimeTransactions.completeSuccess(
            frozen, claim.leaseToken(), frozen.outputResourceIds()));

    // 校验 Run 状态与 pin 清理
    CanvasFunctionRun completed = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.SUCCEEDED, completed.status());
    assertEquals("SUCCEEDED", completed.stage());
    assertTrue(pinRepository.findByRun(canvasId, nodeId, frozen.requestId()).isEmpty());

    // 校验节点按 plan 顺序严格挂接 index 0/1
    List<CanvasResource> attached = resourceRepository.findByOwnerNode(canvasId, nodeId);
    assertEquals(2, attached.size());
    assertEquals(res0.id(), attached.get(0).id());
    assertEquals(nodeId, attached.get(0).ownerNodeId());
    assertEquals(0, attached.get(0).resourceIndex());
    assertEquals("preview.png", attached.get(0).name());
    assertEquals(res1.id(), attached.get(1).id());
    assertEquals(nodeId, attached.get(1).ownerNodeId());
    assertEquals(1, attached.get(1).resourceIndex());
    assertEquals("video.mp4", attached.get(1).name());
  }

  /**
   * 验收测试 2 - TEXT + IMAGE 混合输出发布： TEXT 槽位以内联文本存储（textContent 非空、blobId 为 null、不产生 Blob）， IMAGE 槽位以
   * Blob 存储（blobId 非空、textContent 为 null）； 成功发布后按计划顺序挂接，并精确断言重新读出的数据库行字段值。
   */
  @Test
  void multiOutputTextImagePublishesInlineTextAndMediaBlobInPlanOrder() {
    UUID canvasId = addDocument();
    UUID nodeId =
        addFunctionNode(
            canvasId, "text-image-node", TEXT_IMAGE_DEFINITION.name(), configWithoutReferences());

    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "worker-text-image");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    assertEquals(2, frozen.outputs().size());
    CanvasFunctionFrozenOutput slot0Text = frozen.output(0);
    CanvasFunctionFrozenOutput slot1Image = frozen.output(1);
    assertTrue(slot0Text.inlineText());
    assertFalse(slot1Image.inlineText());

    // 物化 TEXT（内联）与 IMAGE（Blob）
    String inlineText = "This is generated inline summary text.";
    CanvasResource resText =
        materializeTextOutput(canvasId, nodeId, frozen.requestId(), slot0Text, inlineText);
    CanvasResource resImage =
        materializeBlobOutput(canvasId, nodeId, frozen.requestId(), slot1Image);

    // 断言物化后的未挂接状态
    assertNull(resText.blobId());
    assertEquals(inlineText, resText.textContent());
    assertNotNull(resImage.blobId());
    assertNull(resImage.textContent());

    // 原子发布
    assertTrue(
        runtimeTransactions.completeSuccess(
            frozen, claim.leaseToken(), frozen.outputResourceIds()));

    // 重新从数据库精确读取行并断言
    List<CanvasResource> attached = resourceRepository.findByOwnerNode(canvasId, nodeId);
    assertEquals(2, attached.size());

    CanvasResource attachedText = attached.get(0);
    assertEquals(slot0Text.resourceId(), attachedText.id());
    assertEquals(canvasId, attachedText.canvasId());
    assertEquals(nodeId, attachedText.ownerNodeId());
    assertEquals(0, attachedText.resourceIndex());
    assertNull(attachedText.blobId());
    assertEquals("summary.txt", attachedText.name());
    assertEquals(inlineText, attachedText.textContent());

    CanvasResource attachedImage = attached.get(1);
    assertEquals(slot1Image.resourceId(), attachedImage.id());
    assertEquals(canvasId, attachedImage.canvasId());
    assertEquals(nodeId, attachedImage.ownerNodeId());
    assertEquals(1, attachedImage.resourceIndex());
    assertEquals(resImage.blobId(), attachedImage.blobId());
    assertEquals("preview.png", attachedImage.name());
    assertNull(attachedImage.textContent());

    CanvasFunctionRun runInDb = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.SUCCEEDED, runInDb.status());
    assertTrue(pinRepository.findByRun(canvasId, nodeId, frozen.requestId()).isEmpty());
  }

  /**
   * 验收测试 3 - 部分物化与崩溃恢复： 两个槽位中仅物化第一个槽位后模拟 Worker 崩溃与租约过期，新 Worker 认领后（submitState = SUBMITTED）： (1)
   * 仅用已物化的第一个槽位调用 completeSuccess 被拒绝； (2) 重复物化第一个槽位不会产生新行或多余 pin（幂等复用既有资源）； (3)
   * 补齐物化第二个槽位后，completeSuccess 成功发布两个槽位。
   */
  @Test
  void partialMaterializationCrashRecoveryFinishesOnlyAfterMissingSlotMaterialized() {
    UUID canvasId = addDocument();
    UUID nodeId =
        addFunctionNode(
            canvasId,
            "crash-recovery-node",
            TWO_IMAGES_DEFINITION.name(),
            configWithoutReferences());

    // 1. Worker 1 认领并推进提交确认
    ClaimedRun claim1 = claimStartedRun(canvasId, nodeId, REQUEST_1, "worker-1-crash");
    CanvasFunctionFrozenRun initialFrozen = decode(claim1.run());
    CanvasFunctionFrozenRun submittingFrozen =
        runtimeTransactions.beginSubmit(initialFrozen, claim1.leaseToken());
    CanvasFunctionFrozenRun submittedFrozen =
        runtimeTransactions.confirmSubmitted(submittingFrozen, claim1.leaseToken());

    CanvasFunctionFrozenOutput slot0 = submittedFrozen.output(0);
    CanvasFunctionFrozenOutput slot1 = submittedFrozen.output(1);

    // 仅物化 slot 0
    CanvasResource res0First =
        materializeBlobOutput(canvasId, nodeId, submittedFrozen.requestId(), slot0);
    assertEquals(1, pinRepository.findByRun(canvasId, nodeId, submittedFrozen.requestId()).size());

    // 尝试仅用 slot 0 的 id 发布 -> 抛出 IllegalArgumentException
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                submittedFrozen, claim1.leaseToken(), List.of(slot0.resourceId())));

    // 尝试直接发布完整 plan -> 因 slot 1 尚未物化而抛出 IllegalArgumentException
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                submittedFrozen, claim1.leaseToken(), submittedFrozen.outputResourceIds()));

    // 2. 模拟 Worker 1 崩溃与租约过期
    Instant now = Instant.now();
    Instant past = now.minusSeconds(10);
    jdbc.update(
        "update canvas_function_run set lease_until = ? where node_id = ?",
        Timestamp.from(past),
        nodeId);

    // 3. Worker 2 重新认领
    ClaimedRun claim2 =
        workStore.claimNext(now, Duration.ofSeconds(30), "worker-2-recovered").orElseThrow();
    assertEquals(nodeId, claim2.nodeId());
    assertEquals(REQUEST_1, claim2.requestId().toString());
    CanvasFunctionFrozenRun frozen2 = decode(claim2.run());
    assertTrue(frozen2.submitted());
    assertEquals(CanvasFunctionSubmitState.SUBMITTED, frozen2.submitState());

    // 证明已物化的槽位不会被重复插入，且 OUTPUT pin 保持唯一
    CanvasResource res0Second = materializeBlobOutput(canvasId, nodeId, frozen2.requestId(), slot0);
    assertEquals(res0First.id(), res0Second.id());
    assertEquals(res0First.blobId(), res0Second.blobId());
    assertEquals(1, pinRepository.findByRun(canvasId, nodeId, frozen2.requestId()).size());

    // 4. 补齐物化 slot 1
    materializeBlobOutput(canvasId, nodeId, frozen2.requestId(), slot1);
    assertEquals(2, pinRepository.findByRun(canvasId, nodeId, frozen2.requestId()).size());

    // 5. 现在发布成功
    assertTrue(
        runtimeTransactions.completeSuccess(
            frozen2, claim2.leaseToken(), frozen2.outputResourceIds()));

    CanvasFunctionRun finalRun = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.SUCCEEDED, finalRun.status());
    List<CanvasResource> attached = resourceRepository.findByOwnerNode(canvasId, nodeId);
    assertEquals(2, attached.size());
    assertEquals(slot0.resourceId(), attached.get(0).id());
    assertEquals(slot1.resourceId(), attached.get(1).id());
    assertTrue(pinRepository.findByRun(canvasId, nodeId, frozen2.requestId()).isEmpty());
  }

  /** 验收测试 4 - 重复物化幂等性： 同一 Run 对同一槽位重复调用物化入口，返回同一 Resource 行，不产生第二行，不产生多余 pin。 */
  @Test
  void duplicateMaterializationIsIdempotentAndDoesNotDuplicateRowsOrPins() {
    UUID canvasId = addDocument();
    UUID nodeId =
        addFunctionNode(
            canvasId, "dup-mat-node", TWO_IMAGES_DEFINITION.name(), configWithoutReferences());

    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "worker-dup-mat");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    CanvasFunctionFrozenOutput slot0 = frozen.output(0);

    // 第一次物化
    CanvasResource first = materializeBlobOutput(canvasId, nodeId, frozen.requestId(), slot0);
    assertEquals(1, pinRepository.findByRun(canvasId, nodeId, frozen.requestId()).size());
    assertEquals(1, resourceRepository.findByCanvasId(canvasId).size());

    // 第二次物化相同槽位
    CanvasResource second = materializeBlobOutput(canvasId, nodeId, frozen.requestId(), slot0);
    assertEquals(first.id(), second.id());
    assertEquals(first.blobId(), second.blobId());
    assertEquals(1, pinRepository.findByRun(canvasId, nodeId, frozen.requestId()).size());
    assertEquals(1, resourceRepository.findByCanvasId(canvasId).size());
  }

  /**
   * 验收测试 5 - 缺失输出绝不发布： 在多输出槽位中若有任一槽位未物化，completeSuccess 抛出异常并保持 Run 为 RUNNING， 目标资源保持无
   * owner，绝不挂接部分数组。
   */
  @Test
  void missingOutputNeverPublishesAndLeavesRunRunningWithUnownedTargets() {
    UUID canvasId = addDocument();
    UUID nodeId =
        addFunctionNode(
            canvasId,
            "missing-output-node",
            TWO_IMAGES_DEFINITION.name(),
            configWithoutReferences());

    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, "worker-missing-output");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    CanvasFunctionFrozenOutput slot0 = frozen.output(0);

    // 仅物化 slot 0，slot 1 故意不物化
    CanvasResource res0 = materializeBlobOutput(canvasId, nodeId, frozen.requestId(), slot0);

    long versionBefore = version(canvasId);

    // completeSuccess 传入完整计划 ID 抛出异常（因 slot 1 未物化）
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), frozen.outputResourceIds()));

    // 状态必须保持 RUNNING，版本不变
    CanvasFunctionRun runInDb = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, runInDb.status());
    assertEquals(versionBefore, version(canvasId));

    // 已物化的 slot 0 资源保持未挂接（ownerNodeId 为 null）
    CanvasResource res0InDb = resourceRepository.findById(canvasId, res0.id()).orElseThrow();
    assertNull(res0InDb.ownerNodeId());
    assertNull(res0InDb.resourceIndex());

    // 节点没有任何挂接资源，绝不发布部分数组
    assertTrue(resourceRepository.findByOwnerNode(canvasId, nodeId).isEmpty());

    // completeSuccess 传入部分 ID 列表同样抛出异常
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), List.of(slot0.resourceId())));
  }

  /**
   * 验收测试 6 - 过期租约与取消资源保全： (a) 过期租约调用 completeSuccess 或 markUnknown 返回 false，不产生状态变更与资源挂接； (b)
   * 取消或失败时释放全部 pin 并丢弃所有未挂接的预分配输出（无孤儿 Resource 行与孤儿 pin）， 同时被其他活跃 Run 引用的资源必须安全存活。
   */
  @Test
  void staleLeaseFailsSafeAndCancelOrFailureConservesResourcesWhilePreservingSharedPins() {
    UUID canvasId = addDocument();

    // 准备一个被其他 Run 引用的共享资源
    UUID sharedNodeId = addPlainNode(canvasId, "shared-source");
    CanvasResource sharedResource = addBlobResource(canvasId, sharedNodeId, 0, UUID.randomUUID());

    // 节点 1：双输出函数，引用 sharedResource
    UUID node1 =
        addFunctionNode(
            canvasId, "node-1", TWO_IMAGES_DEFINITION.name(), configWithReference(sharedNodeId));
    ClaimedRun claim1 = claimStartedRun(canvasId, node1, REQUEST_1, "worker-run-1");
    CanvasFunctionFrozenRun frozen1 = decode(claim1.run());

    // 节点 2：另一个函数也启动并引用 sharedResource
    UUID node2 =
        addFunctionNode(canvasId, "node-2", DEFINITION.name(), configWithReference(sharedNodeId));
    claimStartedRun(canvasId, node2, REQUEST_2, "worker-run-2");

    // 部分物化 node1 的 slot 0
    CanvasResource slot0Res =
        materializeBlobOutput(canvasId, node1, frozen1.requestId(), frozen1.output(0));

    // (a) 过期租约调用 completeSuccess / markUnknown 返回 false
    assertFalse(
        runtimeTransactions.completeSuccess(
            frozen1, "stale-lease-token", frozen1.outputResourceIds()));
    assertFalse(
        runtimeTransactions.markUnknown(
            node1, REQUEST_1, "stale-lease-token", "stale unknown reason"));

    CanvasFunctionRun run1InDb = runRepository.findByNodeId(node1).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, run1InDb.status());
    assertNull(resourceRepository.findById(canvasId, slot0Res.id()).orElseThrow().ownerNodeId());
    assertTrue(resourceRepository.findByOwnerNode(canvasId, node1).isEmpty());

    // (b) 取消 node 1 的 Run
    runtimeTransactions.cancel(canvasId, node1, REQUEST_1);

    CanvasFunctionRun cancelledRun1 = runRepository.findByNodeId(node1).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.CANCELLED, cancelledRun1.status());

    // node1 的全部 pin 已清空
    assertTrue(pinRepository.findByRun(canvasId, node1, UUID.fromString(REQUEST_1)).isEmpty());

    // node1 未挂接的输出 slot0Res 已被物理删除（无孤儿 Resource 行）
    assertTrue(resourceRepository.findById(canvasId, slot0Res.id()).isEmpty());

    // sharedResource 仍然被 node2 的 Run pin 住，必须安全存活！
    assertTrue(resourceRepository.findById(canvasId, sharedResource.id()).isPresent());
    assertTrue(pinRepository.countByResource(canvasId, sharedResource.id()) > 0);

    // 同样验证 failure 分支的资源保全
    UUID node3 =
        addFunctionNode(
            canvasId, "node-3", TWO_IMAGES_DEFINITION.name(), configWithoutReferences());
    String request3 = "00000000-0000-0000-0000-000000000103";
    ClaimedRun claim3 = claimStartedRun(canvasId, node3, request3, "worker-run-3");
    CanvasFunctionFrozenRun frozen3 = decode(claim3.run());
    CanvasResource slot0Res3 =
        materializeBlobOutput(canvasId, node3, frozen3.requestId(), frozen3.output(0));

    assertTrue(
        runtimeTransactions.failIfRunning(
            node3, request3, claim3.leaseToken(), "execution failed"));
    CanvasFunctionRun failedRun3 = runRepository.findByNodeId(node3).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.FAILED, failedRun3.status());
    assertTrue(pinRepository.findByRun(canvasId, node3, UUID.fromString(request3)).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, slot0Res3.id()).isEmpty());
  }

  /** start 提交的 availableAt 是 claim 的权威时间；不能用独立 wall clock 跨过同一事务的时间边界。 */
  private ClaimedRun claimStartedRun(
      UUID canvasId, UUID nodeId, String requestId, String ownerToken) {
    CanvasFunctionRun started = runtimeTransactions.start(canvasId, nodeId, requestId).run();
    ClaimedRun claim =
        workStore
            .claimNext(started.availableAt(), Duration.ofSeconds(30), ownerToken)
            .orElseThrow();
    assertEquals(nodeId, claim.nodeId(), "claim must correspond to the started node");
    return claim;
  }

  private UUID addFunctionNode(UUID canvasId, String name, String config) {
    return addFunctionNode(canvasId, name, DEFINITION.name(), config);
  }

  private UUID addFunctionNode(UUID canvasId, String name, String functionName, String config) {
    UUID nodeId = UUID.randomUUID();
    canvasStore.addNode(
        new NodeRecord(
            nodeId,
            canvasId,
            name,
            TRANSFORM,
            null,
            new CanvasFunction(functionName, CanvasJson.parseObject(config))));
    return nodeId;
  }

  private UUID addPlainNode(UUID canvasId, String name) {
    UUID nodeId = UUID.randomUUID();
    canvasStore.addNode(new NodeRecord(nodeId, canvasId, name, TRANSFORM, null, null));
    return nodeId;
  }

  /** 宿主物化媒体槽位的忠实双替身：Resource 行与 OUTPUT pin 必须同事务写入，相同 resourceId 幂等返回。 */
  private CanvasResource materializeBlobOutput(
      UUID canvasId, UUID nodeId, UUID requestId, CanvasFunctionFrozenOutput output) {
    return transactions.execute(
        status -> {
          CanvasResource existing =
              resourceRepository.findById(canvasId, output.resourceId()).orElse(null);
          if (existing != null) {
            return existing;
          }
          CanvasResource resource =
              addBlobResource(
                  canvasId, null, null, output.resourceId(), output.name(), output.kind());
          pinRepository.addAll(
              List.of(
                  new CanvasFunctionResourcePin(
                      canvasId,
                      nodeId,
                      requestId,
                      output.resourceId(),
                      CanvasFunctionResourcePin.Role.OUTPUT)));
          return resource;
        });
  }

  /** 宿主物化文本槽位的忠实双替身：内容内联在 Resource 行，无 blob，与 OUTPUT pin 同事务写入。 */
  private CanvasResource materializeTextOutput(
      UUID canvasId, UUID nodeId, UUID requestId, CanvasFunctionFrozenOutput output, String text) {
    return transactions.execute(
        status -> {
          CanvasResource existing =
              resourceRepository.findById(canvasId, output.resourceId()).orElse(null);
          if (existing != null) {
            return existing;
          }
          CanvasResource resource =
              new CanvasResource(
                  output.resourceId(),
                  canvasId,
                  null,
                  null,
                  null,
                  output.name(),
                  text,
                  Instant.now());
          resourceRepository.add(resource);
          pinRepository.addAll(
              List.of(
                  new CanvasFunctionResourcePin(
                      canvasId,
                      nodeId,
                      requestId,
                      output.resourceId(),
                      CanvasFunctionResourcePin.Role.OUTPUT)));
          return resource;
        });
  }

  private CanvasResource materializeTarget(
      UUID canvasId, UUID nodeId, UUID requestId, CanvasFunctionFrozenOutput output) {
    return output.inlineText()
        ? materializeTextOutput(canvasId, nodeId, requestId, output, "text content")
        : materializeBlobOutput(canvasId, nodeId, requestId, output);
  }

  private CanvasResource addBlobResource(
      UUID canvasId,
      UUID ownerNodeId,
      Integer resourceIndex,
      UUID resourceId,
      String name,
      CanvasResourceKind kind) {
    UUID blobId = UUID.randomUUID();
    String mediaType =
        switch (kind) {
          case IMAGE -> "image/png";
          case VIDEO -> "video/mp4";
          case AUDIO -> "audio/mp4";
          case TEXT -> "text/plain";
        };
    blobAccess.put(new CanvasFunctionBlobAccess.BlobFacts(blobId, mediaType, 3L, 1L, 1L, null));
    jdbc.update(
        "insert into storage_blob "
            + "(id, sha256, size_bytes, media_type, width, height, ref_count, state) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
        blobId,
        String.format("%064x", blobId.getLeastSignificantBits() & Long.MAX_VALUE),
        3L,
        mediaType,
        1,
        1,
        1L,
        "ACTIVE");
    CanvasResource resource =
        new CanvasResource(
            resourceId,
            canvasId,
            ownerNodeId,
            resourceIndex,
            blobId,
            name != null ? name : "resource.png",
            null,
            Instant.now());
    resourceRepository.add(resource);
    return resource;
  }

  private CanvasResource addBlobResource(
      UUID canvasId, UUID ownerNodeId, Integer resourceIndex, UUID resourceId) {
    return addBlobResource(
        canvasId, ownerNodeId, resourceIndex, resourceId, "resource.png", CanvasResourceKind.IMAGE);
  }

  private CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    return stateCodec.decode(
        run.stateJson(), catalog.require(stateCodec.functionName(run.stateJson())).function());
  }

  private long version(UUID canvasId) {
    return canvasStore.findDocument(canvasId).orElseThrow().revision();
  }

  private static String configWithoutReferences() {
    return "{\"prompt\":\"prompt\"}";
  }

  private static String configWithReference(UUID sourceNodeId) {
    return "{\"prompt\":\"use reference\",\"source\":{\"type\":\"resource\",\"nodeId\":\""
        + sourceNodeId
        + "\",\"index\":0}}";
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FoundationConfiguration {

    @Bean
    Clock canvasFunctionClock() {
      return Clock.systemUTC();
    }

    @Bean
    CanvasFunctionAdapter foundationAdapter() {
      return new CanvasFunctionAdapter() {
        @Override
        public List<CanvasFunctionDefinition> functions() {
          return List.of(
              DEFINITION, MULTI_MEDIA_DEFINITION, TEXT_IMAGE_DEFINITION, TWO_IMAGES_DEFINITION);
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
        public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {}

        @Override
        public List<UUID> execute(
            CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
          throw new UnsupportedOperationException("foundation test does not execute adapters");
        }
      };
    }

    @Bean
    TestBlobAccess testBlobAccess() {
      return new TestBlobAccess();
    }
  }

  /** 测试只模拟 Core facts；original I/O 不参与事务 foundation。 */
  static final class TestBlobAccess implements CanvasFunctionBlobAccess {

    private final Map<UUID, BlobFacts> facts = new ConcurrentHashMap<>();

    void put(BlobFacts value) {
      facts.put(value.blobId(), value);
    }

    @Override
    public Optional<BlobFacts> findFacts(UUID blobId) {
      return Optional.ofNullable(facts.get(blobId));
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(UUID blobId, long expectedSizeBytes) {
      throw new UnsupportedOperationException("foundation test does not open originals");
    }

    @Override
    public String originalUrl(UUID blobId, long expiresSeconds) {
      throw new UnsupportedOperationException("foundation test does not presign originals");
    }
  }

  /** 忠实模拟 owner/pin 行约束但不引入 Platform Blob ref_count，使 Runtime foundation 保持模块内闭环。 */
  private static final class TestResourceLifecycle implements CanvasResourceLifecycle {

    private final CanvasResourceRepository resources;
    private final CanvasFunctionResourcePinRepository pins;

    private TestResourceLifecycle(
        CanvasResourceRepository resources, CanvasFunctionResourcePinRepository pins) {
      this.resources = resources;
      this.pins = pins;
    }

    @Override
    public void releaseRunPins(UUID canvasId, UUID nodeId, UUID requestId) {
      List<CanvasFunctionResourcePin> released = pins.findByRun(canvasId, nodeId, requestId);
      pins.deleteByRun(canvasId, nodeId, requestId);
      collectUnowned(canvasId, released);
    }

    @Override
    public void releaseNodePins(UUID canvasId, UUID nodeId) {
      List<CanvasFunctionResourcePin> released = pins.findByNode(canvasId, nodeId);
      pins.deleteByNode(canvasId, nodeId);
      collectUnowned(canvasId, released);
    }

    @Override
    public void releaseCanvasPins(UUID canvasId) {
      pins.deleteByCanvas(canvasId);
    }

    @Override
    public void discardResource(UUID canvasId, UUID resourceId) {
      resources.delete(canvasId, resourceId);
    }

    @Override
    public List<CanvasResource> replaceOwnedWithTargets(
        UUID canvasId, UUID nodeId, List<UUID> orderedTargetResourceIds) {
      Objects.requireNonNull(orderedTargetResourceIds, "orderedTargetResourceIds");
      if (orderedTargetResourceIds.isEmpty()) {
        throw new IllegalArgumentException("Function output plan must not be empty");
      }
      Set<UUID> distinct = new LinkedHashSet<>(orderedTargetResourceIds);
      if (distinct.size() != orderedTargetResourceIds.size() || distinct.contains(null)) {
        throw new IllegalArgumentException("Function output targets must be distinct Resources");
      }
      List<CanvasResource> targets = new ArrayList<>(orderedTargetResourceIds.size());
      for (UUID targetResourceId : orderedTargetResourceIds) {
        CanvasResource target =
            resources.findByIdForUpdate(canvasId, targetResourceId).orElse(null);
        if (target == null || target.ownerNodeId() != null || target.resourceIndex() != null) {
          throw new IllegalArgumentException(
              "Function target must be an unowned Resource in the same canvas");
        }
        targets.add(target);
      }
      for (CanvasResource current : resources.findByOwnerNode(canvasId, nodeId)) {
        if (pins.countByResource(canvasId, current.id()) > 0) {
          if (!resources.detachOwner(canvasId, current.id(), nodeId)) {
            throw new IllegalStateException("detach replaced canvas resource failed");
          }
        } else {
          resources.delete(canvasId, current.id());
        }
      }
      List<CanvasResource> attached = new ArrayList<>(targets.size());
      for (int index = 0; index < targets.size(); index++) {
        UUID targetResourceId = targets.get(index).id();
        if (!resources.attachOwner(canvasId, targetResourceId, nodeId, index)) {
          throw new IllegalStateException("attach Function target Resource failed");
        }
        attached.add(targets.get(index).withSlot(nodeId, index));
      }
      return List.copyOf(attached);
    }

    @Override
    public void discardUnownedTargets(UUID canvasId, Collection<UUID> targetResourceIds) {
      Objects.requireNonNull(targetResourceIds, "targetResourceIds");
      for (UUID resourceId : new LinkedHashSet<>(targetResourceIds)) {
        if (resourceId == null) {
          continue;
        }
        resources
            .findByIdForUpdate(canvasId, resourceId)
            .filter(resource -> resource.ownerNodeId() == null)
            .ifPresent(resource -> resources.delete(canvasId, resource.id()));
      }
    }

    @Override
    public void deleteCanvasResources(UUID canvasId) {
      for (CanvasResource resource : resources.findByCanvasId(canvasId)) {
        resources.delete(canvasId, resource.id());
      }
    }

    private void collectUnowned(UUID canvasId, List<CanvasFunctionResourcePin> released) {
      Set<UUID> resourceIds = new LinkedHashSet<>();
      for (CanvasFunctionResourcePin pin : released) {
        resourceIds.add(pin.resourceId());
      }
      for (UUID resourceId : resourceIds) {
        if (pins.countByResource(canvasId, resourceId) == 0) {
          resources
              .findByIdForUpdate(canvasId, resourceId)
              .filter(resource -> resource.ownerNodeId() == null)
              .ifPresent(resource -> resources.delete(canvasId, resource.id()));
        }
      }
    }
  }
}
