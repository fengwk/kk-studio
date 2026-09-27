package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownResolution;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresCanvasInfraTestSupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
      new CanvasFunctionDefinition(
          "test.image",
          "Test Image",
          ARGS_SCHEMA,
          CanvasResourceKind.IMAGE,
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
        materializeTarget(canvasId, nodeId, first.requestId(), firstFrozen.targetResourceId());
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
        materializeTarget(canvasId, targetNodeId, frozen.requestId(), frozen.targetResourceId());
    assertTrue(
        runtimeTransactions.completeSuccess(
            frozen, claim.leaseToken(), List.of(frozen.targetResourceId())));

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
        materializeTarget(
            canvasId, targetNodeId, submitted.requestId(), submitted.targetResourceId());
    assertEquals(2, pinRepository.findByRun(canvasId, targetNodeId, submitted.requestId()).size());

    // 6. completeSuccess 原子收敛为 SUCCEEDED 并释放 pin
    assertTrue(
        runtimeTransactions.completeSuccess(
            submitted, claim.leaseToken(), List.of(submitted.targetResourceId())));

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
        materializeTarget(canvasId, targetNodeId, frozen.requestId(), frozen.targetResourceId());

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
            List.of(frozenResumed.targetResourceId())));

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
        materializeTarget(canvasId, targetNodeId, frozen1.requestId(), frozen1.targetResourceId());
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
        materializeTarget(canvasId, targetNodeId, frozen2.requestId(), frozen2.targetResourceId());
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
        materializeTarget(canvasId, nodeId, frozen.requestId(), frozen.targetResourceId());

    // 结果列表为空
    assertThrows(
        IllegalArgumentException.class,
        () -> runtimeTransactions.completeSuccess(frozen, claim.leaseToken(), List.of()));

    // 结果列表包含多余元素
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), List.of(frozen.targetResourceId(), UUID.randomUUID())));

    // 结果列表 ID 不匹配预分配目标
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), List.of(UUID.randomUUID())));

    // 失效租约写回返回 false，状态保持 RUNNING
    assertFalse(
        runtimeTransactions.completeSuccess(
            frozen, "stale-lease-token", List.of(frozen.targetResourceId())));
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
        materializeTarget(canvasId, nodeId, frozen.requestId(), frozen.targetResourceId());

    assertFalse(
        runtimeTransactions.completeSuccess(frozen, "stale", List.of(frozen.targetResourceId())));
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
            frozen, claim.leaseToken(), List.of(frozen.targetResourceId())));
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
                frozen, claim.leaseToken(), List.of(frozen.targetResourceId())));
    CanvasResource target =
        materializeTarget(canvasId, nodeId, frozen.requestId(), frozen.targetResourceId());
    blobAccess.put(
        new CanvasFunctionBlobAccess.BlobFacts(
            target.blobId(), "video/mp4", 3L, null, null, 1_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), List.of(frozen.targetResourceId())));
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
    UUID nodeId = UUID.randomUUID();
    canvasStore.addNode(
        new NodeRecord(
            nodeId,
            canvasId,
            name,
            TRANSFORM,
            null,
            new CanvasFunction(DEFINITION.name(), CanvasJson.parseObject(config))));
    return nodeId;
  }

  private UUID addPlainNode(UUID canvasId, String name) {
    UUID nodeId = UUID.randomUUID();
    canvasStore.addNode(new NodeRecord(nodeId, canvasId, name, TRANSFORM, null, null));
    return nodeId;
  }

  /** 宿主物化的忠实双替身：Resource 行与 OUTPUT pin 必须同事务写入，才能同时满足 pin→resource 外键与「pin 只指向真实资源」的约定。 */
  private CanvasResource materializeTarget(
      UUID canvasId, UUID nodeId, UUID requestId, UUID targetResourceId) {
    return transactions.execute(
        status -> {
          CanvasResource resource = addBlobResource(canvasId, null, null, targetResourceId);
          pinRepository.addAll(
              List.of(
                  new CanvasFunctionResourcePin(
                      canvasId,
                      nodeId,
                      requestId,
                      targetResourceId,
                      CanvasFunctionResourcePin.Role.OUTPUT)));
          return resource;
        });
  }

  private CanvasResource addBlobResource(
      UUID canvasId, UUID ownerNodeId, Integer resourceIndex, UUID resourceId) {
    UUID blobId = UUID.randomUUID();
    blobAccess.put(new CanvasFunctionBlobAccess.BlobFacts(blobId, "image/png", 3L, 1L, 1L, null));
    jdbc.update(
        "insert into storage_blob "
            + "(id, sha256, size_bytes, media_type, width, height, ref_count, state) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
        blobId,
        String.format("%064x", blobId.getLeastSignificantBits() & Long.MAX_VALUE),
        3L,
        "image/png",
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
            "resource.png",
            null,
            Instant.now());
    resourceRepository.add(resource);
    return resource;
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
          return List.of(DEFINITION);
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
    public CanvasResource replaceOwnedWithTarget(
        UUID canvasId, UUID nodeId, UUID targetResourceId) {
      CanvasResource target = resources.findByIdForUpdate(canvasId, targetResourceId).orElseThrow();
      for (CanvasResource current : resources.findByOwnerNode(canvasId, nodeId)) {
        if (pins.countByResource(canvasId, current.id()) > 0) {
          resources.detachOwner(canvasId, current.id(), nodeId);
        } else {
          resources.delete(canvasId, current.id());
        }
      }
      if (!resources.attachOwner(canvasId, targetResourceId, nodeId, 0)) {
        throw new IllegalStateException("attach test target failed");
      }
      return new CanvasResource(
          target.id(),
          target.canvasId(),
          nodeId,
          0,
          target.blobId(),
          target.name(),
          target.textContent(),
          target.createdAt());
    }

    @Override
    public void discardUnownedTarget(UUID canvasId, UUID targetResourceId) {
      resources
          .findByIdForUpdate(canvasId, targetResourceId)
          .filter(resource -> resource.ownerNodeId() == null)
          .ifPresent(resource -> resources.delete(canvasId, resource.id()));
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
