package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
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
  public UUID materializeTarget(UUID targetResourceId, InputStream content) {
    CanvasFunctionFrozenRun frozen = current.get();
    if (!frozen.targetResourceId().equals(targetResourceId)) {
      throw new IllegalArgumentException(
          "adapter may only materialize the frozen targetResourceId");
    }
    ensureRunning();
    CanvasResource resource =
        materializer.materialize(
            frozen.canvasId(), frozen.targetResourceId(), frozen.outputName(), content);
    if (!resource.id().equals(frozen.targetResourceId())
        || !resource.canvasId().equals(frozen.canvasId())
        || resource.ownerNodeId() != null
        || resource.resourceIndex() != null) {
      throw new IllegalStateException("materializer returned a Resource outside the frozen target");
    }
    return resource.id();
  }

  CanvasFunctionFrozenRun currentRun() {
    return current.get();
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
