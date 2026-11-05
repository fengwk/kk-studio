package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasFunctionUnknownResolution;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** 两阶段提交与恢复不变量：提交意图先落库、SUBMITTING 崩溃收敛 UNKNOWN、人工决议、checkpoint 围栏与取消。 */
@Import(CanvasFunctionRuntimeTestSupport.FoundationConfiguration.class)
class CanvasFunctionSubmitRecoveryTest extends CanvasFunctionRuntimeTestSupport {

  /** 验收证据 1：提交意图在外部提交前持久化（SUBMITTING -> SUBMITTED -> SUCCEEDED），成功后原子挂接唯一资源并释放全部 pin。 */
  @Test
  void submitIntentPersistedBeforeExternalSubmitAndHappyPathReleasesPins() {
    UUID canvasId = addDocument();
    UUID sourceNodeId = addPlainNode(canvasId, "source");
    UUID targetNodeId = addFunctionNode(canvasId, "target", configWithReference(sourceNodeId));
    CanvasResource source = addBlobResource(canvasId, sourceNodeId, 0, UUID.randomUUID());

    // 1. start 构造 PENDING 状态并落库 INPUT pin
    CanvasFunctionRun ready = startRun(canvasId, targetNodeId, REQUEST_1).run();
    assertEquals(CanvasFunctionRunStatus.READY, ready.status());
    CanvasFunctionFrozenRun frozenReady = decode(ready);
    assertEquals(CanvasFunctionSubmitState.PENDING, frozenReady.submitState());
    assertEquals("QUEUED", frozenReady.stage());
    assertEquals(1, pinRepository.findByRun(canvasId, targetNodeId, ready.requestId()).size());

    // 2. Worker 认领 Run
    ClaimedRun claim = workStore.claimNext(Duration.ofSeconds(30), UUID.randomUUID()).orElseThrow();
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

    ClaimedRun claim = claimStartedRun(canvasId, targetNodeId, REQUEST_1, UUID.randomUUID());
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
    assertFalse(runRepository.holdsLease(unknownRun.nodeId(), claim.leaseToken()));

    // UNKNOWN 状态下必须保留 INPUT 和 OUTPUT pin，预分配目标资源不能被丢弃
    assertFalse(pinRepository.findByRun(canvasId, targetNodeId, unknownRun.requestId()).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, target.id()).isPresent());

    // UNKNOWN 状态下拒绝不同 requestId 的 start，也拒绝直接 cancel
    assertThrows(
        CanvasFunctionRunException.class, () -> startRun(canvasId, targetNodeId, REQUEST_2));
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
        workStore.claimNext(Duration.ofSeconds(30), UUID.randomUUID()).orElseThrow();
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
    ClaimedRun claim1 = claimStartedRun(canvasId, targetNodeId, REQUEST_1, UUID.randomUUID());
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
    CanvasFunctionRun replacement = startRun(canvasId, targetNodeId, REQUEST_2).run();
    ClaimedRun claim2 =
        workStore.claimNext(Duration.ofSeconds(30), UUID.randomUUID()).orElseThrow();
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

  /** 外层事务回滚必须同时撤销 checkpoint state 与 document version，不能留下部分提交。 */
  @Test
  void checkpointRollbackLeavesRunAndVersionUntouched() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, UUID.randomUUID());
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
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, UUID.randomUUID());

    runtimeTransactions.checkpoint(
        canvasId, nodeId, REQUEST_1, claim.leaseToken(), "SUBMITTED", Map.of("jobId", "job"));
    assertEquals(2L, version(canvasId));
    assertEquals("SUBMITTED", runRepository.findByNodeId(nodeId).orElseThrow().stage());
    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () ->
            runtimeTransactions.checkpoint(
                canvasId, nodeId, REQUEST_1, UUID.randomUUID(), "LATE", Map.of()));
    assertFalse(
        runtimeTransactions.failIfRunning(nodeId, REQUEST_1, UUID.randomUUID(), "ignored failure"));
    assertTrue(
        runtimeTransactions.failIfRunning(nodeId, REQUEST_1, claim.leaseToken(), "safe failure"));
    CanvasFunctionRun failed = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.FAILED, failed.status());
    assertEquals("safe failure", failed.error());
    assertTrue(pinRepository.findByRun(canvasId, nodeId, failed.requestId()).isEmpty());
    assertEquals(3L, version(canvasId));
  }

  /** checkpoint 在 document 或 Function node 消失后必须内部取消，绝不重建已删除聚合。 */
  @Test
  void checkpointCancelsAfterAggregateDisappears() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, UUID.randomUUID());
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

  /** cancel 只接受当前 Run 的 requestId：陈旧 requestId 必须冲突，且不改变已有 READY Run。 */
  @Test
  void cancelRejectsAStaleRequestId() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    startRun(canvasId, nodeId, REQUEST_1);

    assertThrows(
        CanvasFunctionRunException.class,
        () ->
            transactions.execute(
                status -> runtimeTransactions.cancel(canvasId, nodeId, REQUEST_2)));
    assertEquals(
        CanvasFunctionRunStatus.READY, runRepository.findByNodeId(nodeId).orElseThrow().status());

    // 当前 requestId 才能取消。
    CanvasFunctionRun cancelled =
        transactions.execute(status -> runtimeTransactions.cancel(canvasId, nodeId, REQUEST_1));
    assertEquals(CanvasFunctionRunStatus.CANCELLED, cancelled.status());
    assertEquals(0, catalogSource.openLeases());
  }

  /** resolve 只接受当前 Run 的 requestId：不匹配必须冲突，避免把别的请求的冻结事实解除。 */
  @Test
  void resolveRejectsAStaleRequestId() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    startRun(canvasId, nodeId, REQUEST_1);

    assertThrows(
        CanvasFunctionRunException.class,
        () ->
            transactions.execute(
                status ->
                    runtimeTransactions.resolve(
                        canvasId,
                        nodeId,
                        REQUEST_2,
                        CanvasFunctionUnknownResolution.RESUME,
                        "verified")));
    assertEquals(
        CanvasFunctionRunStatus.READY, runRepository.findByNodeId(nodeId).orElseThrow().status());
    assertEquals(0, catalogSource.openLeases());
  }
}
