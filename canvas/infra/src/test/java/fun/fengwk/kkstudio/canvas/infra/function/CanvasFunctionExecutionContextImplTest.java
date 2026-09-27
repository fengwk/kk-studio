package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Execution context 只允许 frozen original/target，并验证对象长度与事务 checkpoint 委托。 */
class CanvasFunctionExecutionContextImplTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SOURCE_NODE = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000006");
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final String LEASE = "lease";

  private CanvasFunctionRunRepository runs;
  private CanvasFunctionRunTransactions transactions;
  private CanvasFunctionBlobAccess blobAccess;
  private CanvasResourceMaterializer materializer;
  private CanvasFunctionFrozenRun frozen;
  private ClaimedRun claim;

  @BeforeEach
  void setUp() {
    runs = mock(CanvasFunctionRunRepository.class);
    transactions = mock(CanvasFunctionRunTransactions.class);
    blobAccess = mock(CanvasFunctionBlobAccess.class);
    materializer = mock(CanvasResourceMaterializer.class);
    CanvasFunctionDefinition definition =
        new CanvasFunctionDefinition(
            "test.image",
            "Test Image",
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
    CanvasFunctionFrozenReference reference =
        new CanvasFunctionFrozenReference(
            SOURCE_NODE,
            0,
            RESOURCE,
            BLOB,
            CanvasResourceKind.IMAGE,
            "source.png",
            "image/png",
            3L,
            1L,
            1L,
            null);
    frozen =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            definition,
            CanvasJson.parseObject("{}"),
            List.of(reference),
            "output.png",
            TARGET,
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    CanvasFunctionRun running =
        new CanvasFunctionRun(
            NODE,
            REQUEST,
            CanvasFunctionRunStatus.RUNNING,
            1,
            null,
            LEASE,
            Instant.EPOCH.plusSeconds(60),
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.EPOCH,
            Instant.EPOCH);
    claim = new ClaimedRun(running);
    when(runs.findByNodeId(NODE)).thenReturn(Optional.of(running));
  }

  /** 只能读取冻结清单内的输入资源，且传递清单锁定的长度。 */
  @Test
  void opensOnlyFrozenOriginalWithFrozenLength() {
    CanvasFunctionResourceStream stream =
        new CanvasFunctionResourceStream(
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            3L,
            new ByteArrayInputStream(new byte[0]));
    when(blobAccess.openOriginal(BLOB, 3L)).thenReturn(stream);
    CanvasFunctionExecutionContextImpl context = context();

    assertEquals(stream, context.openOriginal(frozen.manifest().get(0)));
    verify(blobAccess).openOriginal(BLOB, 3L);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.openOriginal(
                new CanvasFunctionFrozenReference(
                    SOURCE_NODE,
                    0,
                    RESOURCE,
                    UUID.randomUUID(),
                    CanvasResourceKind.IMAGE,
                    "other.png",
                    "image/png",
                    3L,
                    1L,
                    1L,
                    null)));
  }

  /** 只能物化预分配的目标资源 ID，且宿主返回不匹配资源时必须报错。 */
  @Test
  void materializesOnlyFrozenTargetAndOutputKind() {
    when(materializer.materialize(
            eq(CANVAS), eq(NODE), eq(REQUEST), eq(TARGET), anyString(), any(InputStream.class)))
        .thenReturn(
            new CanvasResource(
                TARGET, CANVAS, null, null, BLOB, "output.png", null, Instant.EPOCH));
    CanvasFunctionExecutionContextImpl context = context();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.materializeTarget(
                UUID.randomUUID(), new ByteArrayInputStream(new byte[] {1, 2, 3})));
    assertEquals(
        TARGET, context.materializeTarget(TARGET, new ByteArrayInputStream(new byte[] {1, 2, 3})));
    verify(materializer)
        .materialize(
            eq(CANVAS),
            eq(NODE),
            eq(REQUEST),
            eq(TARGET),
            eq("output.png"),
            any(InputStream.class));
  }

  /** 只有在 Run 处于 RUNNING 且租约有效时才允许预签名原图 URL。 */
  @Test
  void presignsOnlyFrozenOriginalWhileRunIsRunning() {
    when(blobAccess.originalUrl(BLOB, 120L)).thenReturn("https://s3.example/object");
    CanvasFunctionExecutionContextImpl context = context();

    assertEquals(
        "https://s3.example/object", context.presignOriginal(frozen.manifest().get(0), 120L));
    verify(blobAccess).originalUrl(BLOB, 120L);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.presignOriginal(
                new CanvasFunctionFrozenReference(
                    SOURCE_NODE,
                    0,
                    RESOURCE,
                    UUID.randomUUID(),
                    CanvasResourceKind.IMAGE,
                    "other.png",
                    "image/png",
                    3L,
                    1L,
                    1L,
                    null),
                120L));

    when(runs.findByNodeId(NODE)).thenReturn(Optional.empty());
    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> context.presignOriginal(frozen.manifest().get(0), 120L));
  }

  /** isRunning 检查状态、requestId、租约 token 及租约到期时间。 */
  @Test
  void isRunningChecksStatusLeaseAndExpiration() {
    CanvasFunctionExecutionContextImpl context = context();
    assertTrue(context.isRunning());

    // 租约过期
    CanvasFunctionRun expired =
        new CanvasFunctionRun(
            NODE,
            REQUEST,
            CanvasFunctionRunStatus.RUNNING,
            1,
            null,
            LEASE,
            Instant.EPOCH.minusSeconds(1),
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.EPOCH,
            Instant.EPOCH);
    when(runs.findByNodeId(NODE)).thenReturn(Optional.of(expired));
    assertFalse(context.isRunning());

    // 本地 ownership 丢失
    AtomicBoolean ownershipLost = new AtomicBoolean(true);
    CanvasFunctionExecutionContextImpl lostContext = context(ownershipLost);
    assertFalse(lostContext.isRunning());
  }

  /** checkpoint 委托短事务并更新当前上下文中的 frozen run。 */
  @Test
  void checkpointDelegatesToTransactionsAndUpdatesCurrent() {
    CanvasFunctionFrozenRun next =
        new CanvasFunctionFrozenRun(
            frozen.canvasId(),
            frozen.nodeId(),
            frozen.nodeName(),
            frozen.requestId(),
            frozen.definition(),
            frozen.args(),
            frozen.manifest(),
            frozen.outputName(),
            frozen.targetResourceId(),
            CanvasFunctionSubmitState.SUBMITTING,
            "SUBMITTING",
            Map.of("jobId", "job"));
    when(transactions.checkpoint(
            eq(CANVAS),
            eq(NODE),
            eq(REQUEST.toString()),
            eq(LEASE),
            eq("SUBMITTING"),
            eq(Map.of("jobId", "job"))))
        .thenReturn(next);
    CanvasFunctionExecutionContextImpl context = context();

    context.checkpoint("SUBMITTING", Map.of("jobId", "job"));

    assertEquals(next, context.currentRun());
  }

  /** checkpoint CAS 失败抛出取消异常且不更新本地 currentRun。 */
  @Test
  void checkpointCasCancellationPropagatesWithoutUpdatingCurrent() {
    when(transactions.checkpoint(any(), any(), anyString(), anyString(), anyString(), any()))
        .thenThrow(new CanvasFunctionInternalCancellation("no longer RUNNING"));
    CanvasFunctionExecutionContextImpl context = context();

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> context.checkpoint("SUBMITTING", Map.of("jobId", "job")));

    assertEquals(
        frozen, context.currentRun(), "failed checkpoint must not write back the old frozen run");
  }

  /** 本地 ownership 已丢失时在调用事务前直接快速拒绝。 */
  @Test
  void checkpointRejectsLocallyLostOwnershipBeforeCallingTransactions() {
    CanvasFunctionExecutionContextImpl context = context(new AtomicBoolean(true));

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> context.checkpoint("SUBMITTING", Map.of("jobId", "job")));
    verify(transactions, never())
        .checkpoint(any(), any(), anyString(), anyString(), anyString(), any());
  }

  /** replace 允许在提交阶段推进提交事实后刷新上下文，但拒绝异构 Run。 */
  @Test
  void replaceValidatesSameRunIdentity() {
    CanvasFunctionExecutionContextImpl context = context();
    CanvasFunctionFrozenRun updated =
        new CanvasFunctionFrozenRun(
            frozen.canvasId(),
            frozen.nodeId(),
            frozen.nodeName(),
            frozen.requestId(),
            frozen.definition(),
            frozen.args(),
            frozen.manifest(),
            frozen.outputName(),
            frozen.targetResourceId(),
            CanvasFunctionSubmitState.SUBMITTED,
            "SUBMITTED",
            Map.of());

    context.replace(updated);
    assertEquals(updated, context.currentRun());

    CanvasFunctionFrozenRun differentRun =
        new CanvasFunctionFrozenRun(
            frozen.canvasId(),
            frozen.nodeId(),
            frozen.nodeName(),
            UUID.randomUUID(),
            frozen.definition(),
            frozen.args(),
            frozen.manifest(),
            frozen.outputName(),
            frozen.targetResourceId(),
            CanvasFunctionSubmitState.SUBMITTED,
            "SUBMITTED",
            Map.of());
    assertThrows(IllegalArgumentException.class, () -> context.replace(differentRun));
  }

  private CanvasFunctionExecutionContextImpl context() {
    return context(new AtomicBoolean());
  }

  private CanvasFunctionExecutionContextImpl context(AtomicBoolean ownershipLost) {
    ObjectProvider<CanvasResourceMaterializer> materializerProvider = provider(materializer);
    return new CanvasFunctionExecutionContextImpl(
        runs,
        transactions,
        blobAccess,
        materializerProvider,
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        claim,
        ownershipLost,
        frozen);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
