package fun.fengwk.kkstudio.core.studio.function;

import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.storage.S3ObjectStream;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionResourceStream;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 单个 frozen run 的受限执行上下文（blob 访问经全局 StorageBlobManager 的对象键）。 */
final class CanvasFunctionExecutionContextImpl implements CanvasFunctionExecutionContext {

  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionRunStateCodec stateCodec;
  private final S3StorageService storageService;
  private final StorageBlobManager blobManager;
  private final CanvasResourceMaterializer materializer;
  private final Clock clock;
  private final AtomicReference<CanvasFunctionFrozenRun> current;

  CanvasFunctionExecutionContextImpl(
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionRunStateCodec stateCodec,
      ObjectProvider<S3StorageService> storageServices,
      ObjectProvider<StorageBlobManager> blobManagers,
      ObjectProvider<CanvasResourceMaterializer> materializers,
      Clock clock,
      CanvasFunctionFrozenRun frozen) {
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    storageService =
        Objects.requireNonNull(
            storageServices.getIfAvailable(), "S3 storage is required for Canvas Function runtime");
    blobManager =
        Objects.requireNonNull(
            blobManagers.getIfAvailable(),
            "StorageBlobManager is required for Canvas Function runtime");
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
    requireManifestReference(reference);
    ensureRunning();
    S3ObjectStream object =
        storageService.readObject(StorageObjectKeys.blobOriginal(reference.blobId()));
    if (object.metadata().contentLength() != reference.sizeBytes()) {
      try {
        object.close();
      } catch (IOException closeError) {
        throw new IllegalStateException("failed to close mismatched S3 object stream", closeError);
      }
      throw new IllegalArgumentException("S3 original length does not match frozen blob size");
    }
    return new CanvasFunctionResourceStream(
        object.inputStream(), object.metadata().contentLength(), object);
  }

  @Override
  public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
    requireManifestReference(reference);
    ensureRunning();
    return blobManager.presignOriginalUrl(reference.blobId()).getUrl();
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

  private void requireManifestReference(CanvasFunctionFrozenReference reference) {
    CanvasFunctionFrozenRun frozen = current.get();
    if (!frozen.manifest().contains(reference)) {
      throw new IllegalArgumentException("reference is not part of the frozen manifest");
    }
  }
}
