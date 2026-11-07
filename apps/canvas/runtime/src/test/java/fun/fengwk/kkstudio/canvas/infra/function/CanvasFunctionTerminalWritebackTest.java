package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import fun.fengwk.kkstudio.canvas.BlobFacts;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;

import java.util.List;
import java.util.UUID;

/** 终态写回不变量：成功发布只接受完整物化的冻结计划，过期租约与已删除节点只能 no-op，失败/取消保留共享资源。 */
@Import(CanvasFunctionRuntimeTestSupport.FoundationConfiguration.class)
class CanvasFunctionTerminalWritebackTest extends CanvasFunctionRuntimeTestSupport {

  /** 验收证据 4：结果列表不匹配（空或多元素）直接失败且不发布目标；过期租约写回被拒绝。 */
  @Test
  void completeSuccessRejectsMismatchAndStaleTokens() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, UUID.randomUUID());
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
        runtimeTransactions.completeSuccess(frozen, UUID.randomUUID(), frozen.outputResourceIds()));
    assertFalse(
        runtimeTransactions.markUnknown(
            nodeId, frozen.requestId(), UUID.randomUUID(), "stale unknown reason"));

    CanvasFunctionRun runInDb = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, runInDb.status());
    assertTrue(runRepository.holdsLease(runInDb.nodeId(), claim.leaseToken()));

    // target 仍然保持未挂接
    CanvasResource unowned = resourceRepository.findById(canvasId, target.id()).orElseThrow();
    assertNull(unowned.ownerNodeId());
  }

  /** completeSuccess 的失效 token 必须在读取/交换 target 前被拒绝，不能污染可见资源。 */
  @Test
  void completeSuccessWithStaleLeaseIsANoOp() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, UUID.randomUUID());
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    CanvasResource target =
        materializeTarget(canvasId, nodeId, frozen.requestId(), frozen.output(0));

    assertFalse(
        runtimeTransactions.completeSuccess(frozen, UUID.randomUUID(), frozen.outputResourceIds()));
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, current.status());
    assertTrue(runRepository.holdsLease(current.nodeId(), claim.leaseToken()));
    assertTrue(resourceRepository.findById(canvasId, target.id()).isPresent());
    assertEquals(1L, version(canvasId));
  }

  /** 节点不再是 Function 后，迟到的成功与失败写回都必须保持 no-op。 */
  @Test
  void terminalWritebacksStopAfterNodeIsNoLongerAFunction() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, UUID.randomUUID());
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    jdbc.update("update canvas_node set \"function\" = null where id = ?", nodeId);

    assertFalse(
        runtimeTransactions.completeSuccess(
            frozen, claim.leaseToken(), frozen.outputResourceIds()));
    assertFalse(
        runtimeTransactions.failIfRunning(
            nodeId, frozen.requestId(), claim.leaseToken(), "ignored failure"));
    assertEquals(
        CanvasFunctionRunStatus.RUNNING, runRepository.findByNodeId(nodeId).orElseThrow().status());
    assertEquals(1L, version(canvasId));
  }

  /** success 必须拒绝错误结果列表与尚未物化的 target，并保持 RUNNING/版本不变。 */
  @Test
  void completeSuccessRejectsInvalidOrUnmaterializedTarget() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    ClaimedRun claim = claimStartedRun(canvasId, nodeId, REQUEST_1, UUID.randomUUID());
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
    blobAccess.put(new BlobFacts(target.blobId(), "video/mp4", 3L, null, null, 1_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtimeTransactions.completeSuccess(
                frozen, claim.leaseToken(), frozen.outputResourceIds()));
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, current.status());
    assertEquals(1L, version(canvasId));
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
    ClaimedRun claim1 = claimStartedRun(canvasId, node1, REQUEST_1, UUID.randomUUID());
    CanvasFunctionFrozenRun frozen1 = decode(claim1.run());

    // 节点 2：另一个函数也启动并引用 sharedResource
    UUID node2 =
        addFunctionNode(canvasId, "node-2", DEFINITION.name(), configWithReference(sharedNodeId));
    claimStartedRun(canvasId, node2, REQUEST_2, UUID.randomUUID());

    // 部分物化 node1 的 slot 0
    CanvasResource slot0Res =
        materializeBlobOutput(canvasId, node1, frozen1.requestId(), frozen1.output(0));

    // (a) 过期租约调用 completeSuccess / markUnknown 返回 false
    assertFalse(
        runtimeTransactions.completeSuccess(
            frozen1, UUID.randomUUID(), frozen1.outputResourceIds()));
    assertFalse(
        runtimeTransactions.markUnknown(
            node1, frozen1.requestId(), UUID.randomUUID(), "stale unknown reason"));

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
    ClaimedRun claim3 = claimStartedRun(canvasId, node3, request3, UUID.randomUUID());
    CanvasFunctionFrozenRun frozen3 = decode(claim3.run());
    CanvasResource slot0Res3 =
        materializeBlobOutput(canvasId, node3, frozen3.requestId(), frozen3.output(0));

    assertTrue(
        runtimeTransactions.failIfRunning(
            node3, frozen3.requestId(), claim3.leaseToken(), "execution failed"));
    CanvasFunctionRun failedRun3 = runRepository.findByNodeId(node3).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.FAILED, failedRun3.status());
    assertTrue(pinRepository.findByRun(canvasId, node3, UUID.fromString(request3)).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, slot0Res3.id()).isEmpty());
  }
}
