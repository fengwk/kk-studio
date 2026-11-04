package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 输出计划发布不变量：多槽位原子发布、部分物化崩溃恢复、重复物化幂等与缺失输出绝不发布。 */
@Import(CanvasFunctionRuntimeTestSupport.FoundationConfiguration.class)
class CanvasFunctionOutputPublishingTest extends CanvasFunctionRuntimeTestSupport {

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
        "update durable_work set lease_until = ? where target_type = 'CANVAS_RUN'"
            + " and target_id = ?",
        Timestamp.from(past),
        nodeId);

    // 3. Worker 2 重新认领
    ClaimedRun claim2 =
        workStore.claimNext(Duration.ofSeconds(30), "worker-2-recovered").orElseThrow();
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
}
