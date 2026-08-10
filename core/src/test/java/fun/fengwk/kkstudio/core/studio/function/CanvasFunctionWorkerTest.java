package fun.fengwk.kkstudio.core.studio.function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Worker 只执行匹配的 RUNNING，CAS 取消静默退出，其他失败仅公开固定错误。 */
class CanvasFunctionWorkerTest {

  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          "test-image",
          "Test Image",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
          List.of());

  private CanvasFunctionRunRepository repository;
  private CanvasFunctionAdapter adapter;
  private CanvasFunctionRunTransactions transactions;
  private CanvasFunctionRunStateCodec stateCodec;
  private CanvasFunctionWorker worker;
  private CanvasFunctionFrozenRun frozen;
  private CanvasFunctionRun running;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    repository = mock(CanvasFunctionRunRepository.class);
    adapter = mock(CanvasFunctionAdapter.class);
    transactions = mock(CanvasFunctionRunTransactions.class);
    CanvasFunctionConfigCodec configCodec = new CanvasFunctionConfigCodec(new ObjectMapper());
    stateCodec = new CanvasFunctionRunStateCodec(new ObjectMapper(), configCodec);
    CanvasFunctionModelRegistry registry = mock(CanvasFunctionModelRegistry.class);
    when(registry.require(MODEL.key()))
        .thenReturn(new CanvasFunctionModelRegistry.RegisteredModel(MODEL, adapter));
    ObjectProvider<S3StorageService> storageServices = mock(ObjectProvider.class);
    ObjectProvider<S3PresignService> presignServices = mock(ObjectProvider.class);
    ObjectProvider<CanvasResourceMaterializer> materializers = mock(ObjectProvider.class);
    when(storageServices.getIfAvailable()).thenReturn(mock(S3StorageService.class));
    when(presignServices.getIfAvailable()).thenReturn(mock(S3PresignService.class));
    when(materializers.getIfAvailable()).thenReturn(mock(CanvasResourceMaterializer.class));
    worker =
        new CanvasFunctionWorker(
            repository,
            registry,
            stateCodec,
            transactions,
            storageServices,
            presignServices,
            materializers,
            Clock.fixed(NOW, ZoneOffset.UTC));
    frozen =
        new CanvasFunctionFrozenRun(
            1L,
            2L,
            "output",
            "request",
            MODEL,
            new CanvasFunctionConfig(List.of(new TextSegment("prompt")), Map.of()),
            List.of(),
            "output.png",
            3L,
            "QUEUED",
            Map.of());
    running =
        new CanvasFunctionRun(
            frozen.nodeId(),
            frozen.requestId(),
            CanvasFunctionRunStatus.RUNNING,
            frozen.stage(),
            stateCodec.encode(frozen),
            null,
            NOW);
  }

  @Test
  void completesOnlyTheFrozenTarget() {
    when(repository.findByNodeId(frozen.nodeId())).thenReturn(Optional.of(running));
    when(adapter.enabled()).thenReturn(true);
    when(adapter.execute(any(), any())).thenReturn(List.of(frozen.targetResourceId()));

    worker.run(frozen.nodeId(), frozen.requestId());

    verify(transactions).completeSuccess(eq(frozen), eq(List.of(frozen.targetResourceId())));
    verify(transactions, never()).failIfRunning(anyLong(), any(), any());
  }

  @Test
  void convertsAdapterFailureToFixedPublicError() {
    when(repository.findByNodeId(frozen.nodeId())).thenReturn(Optional.of(running));
    when(adapter.enabled()).thenReturn(true);
    when(adapter.execute(any(), any()))
        .thenThrow(new IllegalStateException("secret provider detail"));

    worker.run(frozen.nodeId(), frozen.requestId());

    verify(transactions)
        .failIfRunning(frozen.nodeId(), frozen.requestId(), "Function execution failed");
    verify(transactions, never()).completeSuccess(any(), any());
  }

  @Test
  void checkpointCasCancellationDoesNotBecomeFailure() {
    when(repository.findByNodeId(frozen.nodeId())).thenReturn(Optional.of(running));
    when(adapter.enabled()).thenReturn(true);
    when(repository.checkpoint(
            eq(frozen.nodeId()), eq(frozen.requestId()), any(), eq("SUBMITTED"), eq(NOW)))
        .thenReturn(false);
    when(adapter.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              invocation
                  .<CanvasFunctionExecutionContext>getArgument(0)
                  .checkpoint("SUBMITTED", Map.of("jobId", "job"));
              return List.of(frozen.targetResourceId());
            });

    worker.run(frozen.nodeId(), frozen.requestId());

    verify(transactions, never()).failIfRunning(anyLong(), any(), any());
    verify(transactions, never()).completeSuccess(any(), any());
  }

  @Test
  void ignoresMissingStaleOrTerminalRuns() {
    when(repository.findByNodeId(frozen.nodeId()))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                new CanvasFunctionRun(
                    frozen.nodeId(),
                    "new-request",
                    CanvasFunctionRunStatus.RUNNING,
                    frozen.stage(),
                    running.stateJson(),
                    null,
                    NOW)))
        .thenReturn(
            Optional.of(
                new CanvasFunctionRun(
                    frozen.nodeId(),
                    frozen.requestId(),
                    CanvasFunctionRunStatus.CANCELLED,
                    "CANCELLED",
                    running.stateJson(),
                    null,
                    NOW)));

    worker.run(frozen.nodeId(), frozen.requestId());
    worker.run(frozen.nodeId(), frozen.requestId());
    worker.run(frozen.nodeId(), frozen.requestId());

    verify(adapter, never()).execute(any(), any());
    verify(transactions, never()).completeSuccess(any(), any());
    verify(transactions, never()).failIfRunning(anyLong(), any(), any());
  }
}
