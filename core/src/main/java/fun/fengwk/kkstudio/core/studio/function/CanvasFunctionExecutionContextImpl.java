package fun.fengwk.kkstudio.core.studio.function;

import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.storage.S3ObjectStream;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourcePaths;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionResourceStream;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 单个 frozen run 的受限执行上下文。 */
final class CanvasFunctionExecutionContextImpl implements CanvasFunctionExecutionContext {

  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionRunStateCodec stateCodec;
  private final S3StorageService storageService;
  private final CanvasResourceMaterializer materializer;
  private final Clock clock;
  private final AtomicReference<CanvasFunctionFrozenRun> current;

  CanvasFunctionExecutionContextImpl(
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionRunStateCodec stateCodec,
      ObjectProvider<S3StorageService> storageServices,
      ObjectProvider<CanvasResourceMaterializer> materializers,
      Clock clock,
      CanvasFunctionFrozenRun frozen) {
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    storageService =
        Objects.requireNonNull(
            storageServices.getIfAvailable(), "S3 storage is required for Canvas Function runtime");
    materializer =
        Objects.requireNonNull(
            materializers.getIfAvailable(),
            "Canvas Resource materializer is required for Canvas Function runtime");
    this.clock = Objects.requireNonNull(clock, "clock");
    current = new AtomicReference<>(Objects.requireNonNull(frozen, "frozen"));
  }

  @Override
  public void checkpoint(String stage, Map<String, Object> adapterState) {
    CanvasFunctionFrozenRun next =
        stateCodec.checkpoint(
            current.get(), stage, Objects.requireNonNull(adapterState, "adapterState"));
    String stateJson = stateCodec.encode(next);
    if (!runRepository.checkpoint(
        next.nodeId(), next.requestId(), stateJson, next.stage(), clock.instant())) {
      throw new CanvasFunctionInternalCancellation(
          "FunctionRun checkpoint CAS failed because the run is no longer RUNNING");
    }
    current.set(next);
  }

  @Override
  public boolean isRunning() {
    CanvasFunctionFrozenRun frozen = current.get();
    return runRepository
        .findByNodeId(frozen.nodeId())
        .filter(
            run ->
                run.status() == CanvasFunctionRunStatus.RUNNING
                    && run.requestId().equals(frozen.requestId()))
        .isPresent();
  }

  @Override
  public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
    CanvasFunctionFrozenRun frozen = current.get();
    if (!frozen.manifest().contains(reference)) {
      throw new IllegalArgumentException("reference is not part of the frozen manifest");
    }
    ensureRunning();
    S3ObjectStream object =
        storageService.readObject(
            CanvasResourcePaths.original(frozen.canvasId(), reference.resourceId()));
    if (object.metadata().contentLength() != reference.size()) {
      try {
        object.close();
      } catch (IOException closeError) {
        throw new IllegalStateException("failed to close mismatched S3 object stream", closeError);
      }
      throw new IllegalArgumentException("S3 original length does not match frozen Resource size");
    }
    return new CanvasFunctionResourceStream(
        object.inputStream(), object.metadata().contentLength(), object);
  }

  @Override
  public long materializeTarget(
      long targetResourceId, String mediaType, long size, InputStream content) {
    CanvasFunctionFrozenRun frozen = current.get();
    if (targetResourceId != frozen.targetResourceId()) {
      throw new IllegalArgumentException(
          "adapter may only materialize the frozen targetResourceId");
    }
    ensureRunning();
    CanvasResource resource =
        materializer.materialize(
            frozen.canvasId(),
            frozen.targetResourceId(),
            frozen.model().outputKind(),
            frozen.outputName(),
            mediaType,
            size,
            content);
    if (resource.id() != frozen.targetResourceId()
        || resource.canvasId() != frozen.canvasId()
        || resource.kind() != frozen.model().outputKind()) {
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
}
