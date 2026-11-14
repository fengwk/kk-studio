package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.BlobFacts;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.infra.platform.PlatformCanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasResourceRepository;

import java.util.Objects;

/**
 * 成功发布前的输出槽位校验：每个冻结槽位必须已按计划物化为无 owner 的 Resource，且内容类型与槽位一致。
 *
 * <p>校验只读 Resource 行与 Blob 事实，不写任何行；发布本身由 {@code CanvasResourceLifecycle} 在同一事务内完成。
 */
final class CanvasFunctionRunOutputs {

  private final PostgresqlCanvasResourceRepository resourceRepository;
  private final PlatformCanvasFunctionBlobAccess blobAccess;

  CanvasFunctionRunOutputs(
      PostgresqlCanvasResourceRepository resourceRepository,
      PlatformCanvasFunctionBlobAccess blobAccess) {
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.blobAccess = Objects.requireNonNull(blobAccess, "blobAccess");
  }

  /** 成功发布前校验单个槽位已按计划物化：无 owner、内容类型与槽位一致。 */
  void requireMaterializedOutput(
      CanvasFunctionFrozenRun frozen, CanvasFunctionFrozenOutput planned) {
    CanvasResource output =
        resourceRepository.findById(frozen.canvasId(), planned.resourceId()).orElse(null);
    if (output == null || output.ownerNodeId() != null || output.resourceIndex() != null) {
      throw new IllegalArgumentException(
          "every frozen output must be materialized as an unowned Resource before publishing");
    }
    if (planned.inlineText()) {
      if (output.blobId() != null || output.textContent() == null) {
        throw new IllegalArgumentException(
            "TEXT output slot must be materialized as inline text Resource");
      }
      return;
    }
    if (output.blobId() == null) {
      throw new IllegalArgumentException(
          "media output slot must be materialized as a blob Resource");
    }
    BlobFacts outputBlob = blobAccess.findFacts(output.blobId()).orElse(null);
    if (outputBlob == null
        || CanvasResourceKind.fromMediaType(outputBlob.mediaType()) != planned.kind()) {
      throw new IllegalArgumentException(
          "output blob kind must match the frozen Function output slot kind");
    }
  }
}
