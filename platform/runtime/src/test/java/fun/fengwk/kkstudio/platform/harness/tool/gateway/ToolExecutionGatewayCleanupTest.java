package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** 目录关闭失败不占住并发配额，不覆盖原始失败，也不阻止已附加的取消通知。 */
class ToolExecutionGatewayCleanupTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");

  @Test
  void rejectedStartReleasesQuotaEvenWhenCatalogCloseFails() {
    AssertionError cleanup = new AssertionError("catalog close");
    Fixture fixture = new Fixture(cleanup);
    Throwable thrown =
        assertThrows(
            Throwable.class,
            () ->
                fixture.gateway.start(
                    ToolGatewayTestSupport.execution(
                        ToolGatewayTestSupport.hostRequest(
                            "call-1", ToolGatewayTestSupport.hostDescriptor("missing"))),
                    fixture.listener));

    assertAll(
        () -> assertSame(cleanup, thrown),
        fixture::assertQuotaAvailable,
        () -> assertTrue(fixture.tool.requests.isEmpty()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  @Test
  void constructionFailureKeepsPrimaryAndSuppressesCatalogError() {
    assertConstructionFailure(new IllegalStateException("construction"));
  }

  @Test
  void fatalConstructionFailureKeepsPrimaryAndSuppressesCatalogError() {
    assertConstructionFailure(new AssertionError("construction"));
  }

  @Test
  void catalogAcquisitionFailureReleasesQuotaWithoutClosingUnacquiredCatalog() {
    Fixture fixture = new Fixture(new AssertionError("must not close"));
    IllegalStateException primary = new IllegalStateException("acquire");
    doThrow(primary).when(fixture.source).acquire();
    assertSame(primary, assertThrows(IllegalStateException.class, fixture::start));
    fixture.assertQuotaAvailable();
    assertEquals(0, fixture.catalog.closedLeases());
    assertTrue(fixture.tool.requests.isEmpty());
  }

  private static void assertConstructionFailure(Throwable primary) {
    AssertionError cleanup = new AssertionError("catalog close");
    Fixture fixture = new Fixture(cleanup);
    // listener bridge 构造时读取租用目录，尚未提交执行任务。
    doThrow(primary).when(fixture.catalogLease).harnessCatalog();
    Throwable thrown = assertThrows(Throwable.class, fixture::start);

    assertAll(
        () -> assertSame(primary, thrown),
        () -> assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed()),
        fixture::assertQuotaAvailable,
        () -> assertTrue(fixture.tool.requests.isEmpty()),
        () -> assertTrue(fixture.executor.queued.isEmpty()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  @Test
  void identicalStartAndCleanupErrorIsNotSelfSuppressed() {
    AssertionError primary = new AssertionError("same failure");
    Fixture fixture = new Fixture(primary);
    doThrow(primary).when(fixture.catalogLease).harnessCatalog();
    Throwable thrown = assertThrows(Throwable.class, fixture::start);

    assertAll(
        () -> assertSame(primary, thrown),
        () -> assertEquals(0, primary.getSuppressed().length),
        fixture::assertQuotaAvailable,
        () -> assertTrue(fixture.tool.requests.isEmpty()));
  }

  @Test
  void rejectedSubmissionKeepsPrimaryWhenGateCleanupFails() {
    assertSubmissionFailure(new RejectedExecutionException("submission rejected"));
  }

  @Test
  void ambiguousSubmissionKeepsPrimaryWhenGateCleanupFails() {
    assertSubmissionFailure(new IllegalStateException("submission uncertain"));
  }

  private static void assertSubmissionFailure(RuntimeException primary) {
    AssertionError cleanup = new AssertionError("catalog close");
    Fixture fixture = new Fixture(cleanup);
    fixture.executor.ambiguousException = primary;
    Throwable thrown = assertThrows(Throwable.class, fixture::start);

    assertAll(
        () -> assertSame(primary, thrown),
        () -> assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed()),
        fixture::assertQuotaAvailable,
        () -> assertTrue(fixture.tool.requests.isEmpty()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  @Test
  void cancelBeforeActivationClosesBothLeasesAndNeverRunsTool() {
    AssertionError cleanup = new AssertionError("catalog close");
    Fixture fixture = new Fixture(cleanup);
    ToolGateway.Handle handle = fixture.start();
    Throwable thrown = assertThrows(Throwable.class, handle::cancel);
    handle.cancel();
    handle.activate();
    fixture.executor.drain();

    assertAll(
        () -> assertSame(cleanup, thrown),
        fixture::assertQuotaAvailable,
        () -> assertTrue(fixture.tool.requests.isEmpty()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  @Test
  void cancelStillNotifiesAttachedHandleWhenBridgeCleanupFails() {
    AssertionError cleanup = new AssertionError("catalog close");
    Fixture fixture = new Fixture(cleanup);
    ToolGateway.Handle handle = fixture.start();
    handle.activate();
    fixture.executor.drain();
    Throwable thrown = assertThrows(Throwable.class, handle::cancel);

    assertAll(
        () -> assertSame(cleanup, thrown),
        () -> assertTrue(fixture.tool.handles.get(0).isCancelled()),
        fixture::assertQuotaAvailable,
        () -> assertEquals(1, fixture.tool.requests.size()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  @Test
  void bridgeFailureRemainsPrimaryWhenAttachedCancellationAlsoFails() {
    AssertionError cleanup = new AssertionError("catalog close");
    IllegalStateException cancellation = new IllegalStateException("handle cancel");
    Fixture fixture = new Fixture(cleanup);
    ToolExecutionGateway.GatewayHandle handle =
        assertInstanceOf(ToolExecutionGateway.GatewayHandle.class, fixture.start());
    handle.attach(
        () -> {
          throw cancellation;
        });
    Throwable thrown = assertThrows(Throwable.class, handle::cancel);

    assertAll(
        () -> assertSame(cleanup, thrown),
        () -> assertArrayEquals(new Throwable[] {cancellation}, cleanup.getSuppressed()),
        fixture::assertQuotaAvailable,
        () -> assertTrue(fixture.tool.requests.isEmpty()));
  }

  @Test
  void identicalBridgeAndAttachedCancellationErrorIsNotSelfSuppressed() {
    AssertionError primary = new AssertionError("same failure");
    Fixture fixture = new Fixture(primary);
    ToolExecutionGateway.GatewayHandle handle =
        assertInstanceOf(ToolExecutionGateway.GatewayHandle.class, fixture.start());
    handle.attach(
        () -> {
          throw primary;
        });
    assertSame(primary, assertThrows(AssertionError.class, handle::cancel));
    assertEquals(0, primary.getSuppressed().length);
    fixture.assertQuotaAvailable();
  }

  @Test
  void activationCleanupErrorReleasesQuotaWithoutExecutingTool() {
    AssertionError cleanup = new AssertionError("catalog close");
    Fixture fixture = new Fixture(cleanup);
    ToolExecutionGateway.GatewayHandle handle =
        assertInstanceOf(ToolExecutionGateway.GatewayHandle.class, fixture.start());
    // 激活前缓存的终态在 activate 内派发；执行任务尚未启动。
    handle.bridge().onComplete(ToolGatewayTestSupport.result("call-1", "ok"));
    Throwable thrown = assertThrows(Throwable.class, handle::activate);
    fixture.executor.drain();

    assertAll(
        () -> assertSame(cleanup, thrown),
        fixture::assertQuotaAvailable,
        () -> assertEquals(1, fixture.listener.terminalInvocations.get()),
        () -> assertTrue(fixture.tool.requests.isEmpty()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  @Test
  void terminalCatalogErrorReleasesQuota() {
    assertTerminalCleanupFailure(new AssertionError("catalog close"));
  }

  @Test
  void terminalCatalogRuntimeExceptionDoesNotDeliverSecondTerminal() {
    assertTerminalCleanupFailure(new IllegalStateException("catalog close"));
  }

  @Test
  void catalogFailureRemainsPrimaryWhenQuotaCloseAlsoFails() {
    assertBothCloseFailures(
        new IllegalStateException("catalog close"), new AssertionError("quota close"));
  }

  @Test
  void identicalCatalogAndQuotaCloseErrorIsNotSelfSuppressed() {
    AssertionError primary = new AssertionError("same failure");
    assertBothCloseFailures(primary, primary);
  }

  private static void assertBothCloseFailures(Throwable primary, Throwable secondary) {
    Fixture fixture = new Fixture(primary);
    fixture.failAdmissionClose(secondary);
    ToolExecutionListener callback = fixture.executeWithoutTerminal(fixture.listener);
    Throwable thrown =
        assertThrows(
            Throwable.class,
            () -> callback.onComplete(ToolGatewayTestSupport.result("call-1", "ok")));

    assertAll(
        () -> assertSame(primary, thrown),
        () ->
            assertArrayEquals(
                primary == secondary ? new Throwable[0] : new Throwable[] {secondary},
                primary.getSuppressed()),
        fixture::assertQuotaAvailable,
        () -> assertEquals(1, fixture.listener.terminalInvocations.get()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  private static void assertTerminalCleanupFailure(Throwable cleanup) {
    Fixture fixture = new Fixture(cleanup);
    ToolExecutionListener callback = fixture.executeWithoutTerminal(fixture.listener);
    Throwable thrown =
        assertThrows(
            Throwable.class,
            () -> callback.onComplete(ToolGatewayTestSupport.result("call-1", "ok")));

    assertAll(
        () -> assertSame(cleanup, thrown),
        fixture::assertQuotaAvailable,
        () -> assertEquals(1, fixture.listener.terminalInvocations.get()),
        () -> assertEquals(1, fixture.tool.requests.size()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  @Test
  void terminalListenerErrorKeepsPrimaryAndSuppressesCleanup() {
    AssertionError primary = new AssertionError("listener");
    AssertionError cleanup = new AssertionError("catalog close");
    assertTerminalFailure(primary, cleanup);
  }

  @Test
  void identicalTerminalAndCleanupErrorIsNotSelfSuppressed() {
    AssertionError primary = new AssertionError("same failure");
    assertTerminalFailure(primary, primary);
  }

  @Test
  void handledListenerRuntimeFailureDoesNotSwallowCleanupError() {
    assertHandledListenerCleanupFailure(
        new IllegalStateException("listener"), new AssertionError("catalog close"));
  }

  @Test
  void handledListenerRuntimeFailureDoesNotSwallowCleanupRuntimeException() {
    assertHandledListenerCleanupFailure(
        new IllegalStateException("listener"), new IllegalArgumentException("catalog close"));
  }

  @Test
  void identicalHandledListenerAndCleanupRuntimeExceptionIsNotSelfSuppressed() {
    IllegalStateException failure = new IllegalStateException("same failure");
    assertHandledListenerCleanupFailure(failure, failure);
  }

  private static void assertHandledListenerCleanupFailure(
      RuntimeException listenerFailure, Throwable cleanup) {
    Fixture fixture = new Fixture(cleanup);
    ToolGateway.Listener listener = mock(ToolGateway.Listener.class);
    doThrow(listenerFailure).when(listener).onSucceeded(any(ToolSuccess.class));
    ToolExecutionListener callback = fixture.executeWithoutTerminal(listener);

    // worker 已退出；回调中的 Runtime 被隔离，但目录关闭失败仍须原样抛出。
    Throwable thrown =
        assertThrows(
            cleanup.getClass(),
            () -> callback.onComplete(ToolGatewayTestSupport.result("call-1", "ok")));
    assertSame(cleanup, thrown);
    assertEquals(0, listenerFailure.getSuppressed().length);
    assertEquals(0, cleanup.getSuppressed().length);
    verify(listener).onSucceeded(any(ToolSuccess.class));
    verify(listener, never()).onUnknown(any());
    fixture.assertQuotaAvailable();
    assertEquals(1, fixture.tool.requests.size());
    assertEquals(1, fixture.catalog.closedLeases());
  }

  @Test
  void workerErrorRemainsPrimaryWhenDeferredCatalogCloseFails() {
    assertWorkerFailure(
        new AssertionError("tool worker"), new IllegalStateException("catalog close"));
  }

  @Test
  void identicalWorkerAndDeferredCleanupErrorIsNotSelfSuppressed() {
    AssertionError primary = new AssertionError("same failure");
    assertWorkerFailure(primary, primary);
  }

  @Test
  void synchronousTerminalCleanupFailureReleasesQuotaAfterWorkerExit() {
    AssertionError cleanup = new AssertionError("catalog close");
    Fixture fixture = new Fixture(cleanup);
    fixture.tool.handler =
        (request, sink) -> sink.onComplete(ToolGatewayTestSupport.result("call-1", "ok"));
    fixture.start().activate();
    assertSame(cleanup, assertThrows(AssertionError.class, fixture.executor::drain));
    fixture.assertQuotaAvailable();
    assertEquals(1, fixture.listener.terminalInvocations.get());
    assertEquals(1, fixture.tool.requests.size());
    assertEquals(1, fixture.catalog.closedLeases());
  }

  @Test
  void workerErrorRetainsCallbackAndDeferredCleanupFailures() {
    assertWorkerCallbackFailure(new AssertionError("worker"), new AssertionError("callback"));
  }

  @Test
  void identicalWorkerAndErrorCallbackFailureIsNotSelfSuppressed() {
    AssertionError primary = new AssertionError("same failure");
    assertWorkerCallbackFailure(primary, primary);
  }

  private static void assertWorkerCallbackFailure(AssertionError primary, AssertionError callback) {
    IllegalStateException cleanup = new IllegalStateException("catalog close");
    Fixture fixture = new Fixture(cleanup);
    ToolGateway.Listener listener = mock(ToolGateway.Listener.class);
    doThrow(callback).when(listener).onUnknown(any());
    fixture.tool.handler =
        (request, sink) -> {
          throw primary;
        };
    fixture.start(listener).activate();
    assertSame(primary, assertThrows(AssertionError.class, fixture.executor::drain));
    assertArrayEquals(
        primary == callback ? new Throwable[] {cleanup} : new Throwable[] {callback, cleanup},
        primary.getSuppressed());
    fixture.assertQuotaAvailable();
    assertEquals(1, fixture.catalog.closedLeases());
  }

  private static void assertWorkerFailure(AssertionError primary, Throwable cleanup) {
    Fixture fixture = new Fixture(cleanup);
    fixture.tool.handler =
        (request, sink) -> {
          throw primary;
        };
    fixture.start().activate();
    Throwable thrown = assertThrows(Throwable.class, fixture.executor::drain);
    assertAll(
        () -> assertSame(primary, thrown),
        () ->
            assertArrayEquals(
                primary == cleanup ? new Throwable[0] : new Throwable[] {cleanup},
                primary.getSuppressed()),
        fixture::assertQuotaAvailable,
        () -> assertEquals(1, fixture.listener.terminalInvocations.get()),
        () -> assertEquals(1, fixture.tool.requests.size()),
        () -> assertEquals(1, fixture.catalog.closedLeases()));
  }

  private static void assertTerminalFailure(AssertionError primary, AssertionError cleanup) {
    Fixture fixture = new Fixture(cleanup);
    ToolGateway.Listener listener = mock(ToolGateway.Listener.class);
    doThrow(primary).when(listener).onSucceeded(any(ToolSuccess.class));
    ToolExecutionListener callback = fixture.executeWithoutTerminal(listener);
    Throwable thrown =
        assertThrows(
            Throwable.class,
            () -> callback.onComplete(ToolGatewayTestSupport.result("call-1", "ok")));

    assertAll(
        () -> assertSame(primary, thrown),
        () ->
            assertArrayEquals(
                primary == cleanup ? new Throwable[0] : new Throwable[] {cleanup},
                primary.getSuppressed()),
        fixture::assertQuotaAvailable,
        () -> assertEquals(1, fixture.tool.requests.size()));
  }

  private static final class Fixture {
    final ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    final TestRuntimeToolCatalogSource catalog =
        TestRuntimeToolCatalogSource.of(ToolGatewayTestSupport.defaultCatalog(tool));
    final RuntimeToolCatalogSource.Lease catalogLease = spy(catalog.acquire());
    final ConcurrencyAdmission admission = spy(new ConcurrencyAdmission(1));
    final ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    final ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    final RuntimeToolCatalogSource source = mock(RuntimeToolCatalogSource.class);
    final ToolExecutionGateway gateway;

    Fixture(Throwable cleanup) {
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                throw cleanup;
              })
          .when(catalogLease)
          .close();
      when(source.acquire()).thenReturn(catalogLease);
      gateway =
          ToolGatewayTestSupport.gateway(
              source,
              new ToolGatewayTestSupport.FakeTransport(),
              new ToolGatewayTestSupport.FakeResourceStager(),
              executor,
              ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
              ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
              admission);
      // 清空构造期 executor 非内联探针，后续队列只记录执行任务。
      executor.drain();
    }

    ToolGateway.Handle start() {
      return start(listener);
    }

    ToolGateway.Handle start(ToolGateway.Listener target) {
      return assertInstanceOf(
              ToolGateway.Started.class,
              gateway.start(
                  ToolGatewayTestSupport.execution(
                      ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
                  target))
          .handle();
    }

    ToolExecutionListener executeWithoutTerminal(ToolGateway.Listener target) {
      AtomicReference<ToolExecutionListener> callback = new AtomicReference<>();
      tool.handler = (request, sink) -> callback.set(sink);
      start(target).activate();
      executor.drain();
      assertEquals(0, catalog.closedLeases());
      return callback.get();
    }

    void failAdmissionClose(Throwable failure) {
      ConcurrencyAdmission.Lease quota = spy(admission.tryAcquire().orElseThrow());
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                throw failure;
              })
          .when(quota)
          .close();
      // 仅注入本次调用的 quota；close 先真实释放，再注入异常。
      doReturn(Optional.of(quota)).doCallRealMethod().when(admission).tryAcquire();
    }

    void assertQuotaAvailable() {
      var acquired = admission.tryAcquire();
      assertTrue(acquired.isPresent(), "目录关闭失败也必须释放并发配额");
      acquired.orElseThrow().close();
    }
  }
}
