package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;

import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个 frozen run 的受限执行上下文。
 *
 * <p>checkpoint 委托给 {@link CanvasFunctionRunTransactions} 的短事务入口：冻结 run 的 stage/adapterState 前进与
 * canvas version + node patch 在同一事务边界收敛；lease fencing/节点消失/取消抛 {@link
 * CanvasFunctionInternalCancellation} 终止 adapter，失败时不更新本地 current（绝不写回旧 run）。
 */
final class CanvasFunctionExecutionContextImpl implements CanvasFunctionExecutionContext {

  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionRunTransactions transactions;
  private final CanvasFunctionBlobAccess blobAccess;
  private final CanvasResourceMaterializer materializer;
  private final Clock clock;
  private final ClaimedRun claim;
  private final AtomicBoolean ownershipLost;
  private final AtomicReference<CanvasFunctionFrozenRun> current;

  CanvasFunctionExecutionContextImpl(
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionRunTransactions transactions,
      CanvasFunctionBlobAccess blobAccess,
      ObjectProvider<CanvasResourceMaterializer> materializers,
      Clock clock,
      ClaimedRun claim,
      AtomicBoolean ownershipLost,
      CanvasFunctionFrozenRun frozen) {
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.blobAccess = Objects.requireNonNull(blobAccess, "blobAccess");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.ownershipLost = Objects.requireNonNull(ownershipLost, "ownershipLost");
    materializer =
        Objects.requireNonNull(
            materializers.getIfAvailable(),
            "Canvas Resource materializer is required for Canvas Function runtime");
    current = new AtomicReference<>(Objects.requireNonNull(frozen, "frozen"));
  }

  @Override
  public void checkpoint(String stage, Map<String, Object> adapterState) {
    // Calls that pass this local fence remain protected by the database token/lease CAS.
    requireOwnership();
    CanvasFunctionFrozenRun frozen = current.get();
    CanvasFunctionFrozenRun next =
        transactions.checkpoint(
            frozen.canvasId(),
            frozen.nodeId(),
            frozen.requestId().toString(),
            claim.leaseToken(),
            Objects.requireNonNull(stage, "stage"),
            Objects.requireNonNull(adapterState, "adapterState"));
    current.set(next);
  }

  @Override
  public boolean isRunning() {
    if (ownershipLost.get()) {
      return false;
    }
    CanvasFunctionFrozenRun frozen = current.get();
    return runRepository
        .findByNodeId(frozen.nodeId())
        .filter(
            run ->
                run.status() == CanvasFunctionRunStatus.RUNNING
                    && run.requestId().equals(frozen.requestId())
                    && Objects.equals(run.leaseToken(), claim.leaseToken())
                    && run.leaseUntil().isAfter(clock.instant()))
        .isPresent();
  }

  @Override
  public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
    requireManifestReference(reference);
    ensureRunning();
    return blobAccess.openOriginal(reference.blobId(), reference.sizeBytes());
  }

  @Override
  public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
    requireManifestReference(reference);
    ensureRunning();
    return blobAccess.originalUrl(reference.blobId(), expiresSeconds);
  }

  @Override
  public UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content) {
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(content, "content");
    CanvasFunctionFrozenRun frozen = requirePlannedOutput(output);
    if (output.inlineText()) {
      throw new IllegalArgumentException(
          "TEXT output must be materialized through materializeTextOutput");
    }
    ensureRunning();
    CanvasResource resource =
        materializer.materializeBlob(
            frozen.canvasId(),
            frozen.nodeId(),
            frozen.requestId(),
            output.resourceId(),
            output.name(),
            content);
    return requireMaterialized(frozen, output, resource);
  }

  @Override
  public UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text) {
    Objects.requireNonNull(output, "output");
    if (text == null) {
      throw new IllegalArgumentException("TEXT output content is required");
    }
    CanvasFunctionFrozenRun frozen = requirePlannedOutput(output);
    if (!output.inlineText()) {
      throw new IllegalArgumentException(
          "only a TEXT output slot can be materialized as inline text");
    }
    ensureRunning();
    CanvasResource resource =
        materializer.materializeText(
            frozen.canvasId(),
            frozen.nodeId(),
            frozen.requestId(),
            output.resourceId(),
            output.name(),
            text);
    return requireMaterialized(frozen, output, resource);
  }

  /** adapter 只能物化冻结输出计划内的槽位；id 与 index 必须同时匹配，避免伪造或错位发布。 */
  private CanvasFunctionFrozenRun requirePlannedOutput(CanvasFunctionFrozenOutput output) {
    CanvasFunctionFrozenRun frozen = current.get();
    CanvasFunctionFrozenOutput planned = frozen.output(output.index());
    if (!planned.equals(output)) {
      throw new IllegalArgumentException("output slot is not part of the frozen output plan");
    }
    return frozen;
  }

  private static UUID requireMaterialized(
      CanvasFunctionFrozenRun frozen, CanvasFunctionFrozenOutput output, CanvasResource resource) {
    if (resource == null
        || !resource.id().equals(output.resourceId())
        || !resource.canvasId().equals(frozen.canvasId())
        || resource.ownerNodeId() != null
        || resource.resourceIndex() != null) {
      throw new IllegalStateException("materializer returned a Resource outside the frozen output");
    }
    return resource.id();
  }

  CanvasFunctionFrozenRun currentRun() {
    return current.get();
  }

  /** Runtime 在提交阶段推进提交事实后刷新进程内冻结计划；只允许同一 Run 的同一目标。 */
  void replace(CanvasFunctionFrozenRun frozen) {
    Objects.requireNonNull(frozen, "frozen");
    CanvasFunctionFrozenRun existing = current.get();
    if (!existing.canvasId().equals(frozen.canvasId())
        || !existing.nodeId().equals(frozen.nodeId())
        || !existing.requestId().equals(frozen.requestId())
        || !existing.outputResourceIds().equals(frozen.outputResourceIds())) {
      throw new IllegalArgumentException("context may only be replaced by the same FunctionRun");
    }
    current.set(frozen);
  }

  private void ensureRunning() {
    if (!isRunning()) {
      throw new CanvasFunctionInternalCancellation("FunctionRun is no longer RUNNING");
    }
  }

  private void requireOwnership() {
    if (ownershipLost.get()) {
      throw new CanvasFunctionInternalCancellation("Canvas Function lease was lost");
    }
  }

  private void requireManifestReference(CanvasFunctionFrozenReference reference) {
    CanvasFunctionFrozenRun frozen = current.get();
    if (!frozen.manifest().contains(reference)) {
      throw new IllegalArgumentException("reference is not part of the frozen manifest");
    }
  }
}
