package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 意图：发送不确定、超时、中断与传输失败一律成为“结果未知”；只有传输层未就绪才是 DAEMON_NOT_READY，且都保留原始 cause。 */
class IdeCommandCallerTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final Duration TIMEOUT = Duration.ofMillis(50);

  @Test
  void rejectsNonPositiveTimeoutAtConstruction() {
    IdeCommandTransport transport = mock(IdeCommandTransport.class);
    assertThrows(
        IllegalArgumentException.class, () -> new IdeCommandCaller(transport, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdeCommandCaller(transport, Duration.ofMillis(-1)));
  }

  @Test
  void sendUncertaintyKeepsTheOriginalCause() {
    EnvironmentCapabilitySendUncertainException cause =
        new EnvironmentCapabilitySendUncertainException("uncertain");
    RecordingTransport transport = new RecordingTransport();
    transport.sendFailure = cause;

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> new IdeCommandCaller(transport, TIMEOUT).callConfirmed(ENV, listViews()));

    assertEquals("IDE_OUTCOME_UNKNOWN", error.reason());
    assertEquals(cause, error.getCause().getCause());
    assertEquals(0, transport.abandoned);
  }

  @Test
  void unavailableAndBusyTransportIsDaemonNotReady() {
    RecordingTransport unavailable = new RecordingTransport();
    unavailable.sendFailure = new EnvironmentCapabilityUnavailableException("unavailable");
    DomainException first =
        assertThrows(
            DomainException.class,
            () -> new IdeCommandCaller(unavailable, TIMEOUT).call(ENV, listViews()));
    assertEquals("DAEMON_NOT_READY", first.reason());
    assertEquals(unavailable.sendFailure, first.getCause());

    RecordingTransport busy = new RecordingTransport();
    busy.sendFailure = new EnvironmentCapabilityBusyException("busy");
    DomainException second =
        assertThrows(
            DomainException.class,
            () -> new IdeCommandCaller(busy, TIMEOUT).call(ENV, listViews()));
    assertEquals("DAEMON_NOT_READY", second.reason());
    assertEquals(busy.sendFailure, second.getCause());
  }

  @Test
  void unavailableNormalCloseHasARecoverableControlReason() {
    RecordingTransport transport = new RecordingTransport();
    transport.sendFailure = new EnvironmentCapabilityUnavailableException("unavailable");
    IdeCommand close =
        IdeCommand.newBuilder().setAction(IdeAction.CLOSE_VIEW).setDiscard(false).build();

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> new IdeCommandCaller(transport, TIMEOUT).callConfirmed(ENV, close));

    assertEquals("IDE_CONTROL_UNAVAILABLE", error.reason());
    assertEquals(transport.sendFailure, error.getCause());
  }

  @Test
  void timeoutAbandonsTheInvocationAndReportsUnknownOutcome() {
    RecordingTransport transport = new RecordingTransport();
    IdeCommandCaller caller = new IdeCommandCaller(transport, TIMEOUT);

    IdeCommandCaller.IdeOutcomeUnknownException error =
        assertThrows(
            IdeCommandCaller.IdeOutcomeUnknownException.class, () -> caller.call(ENV, listViews()));

    assertEquals("environment IDE command timed out", error.getMessage());
    assertEquals(1, transport.abandoned);
    assertEquals(transport.invocationId, transport.abandonedInvocation);
  }

  @Test
  void failedTransportFutureKeepsThePropagatedCause() {
    RuntimeException cause = new IllegalStateException("daemon closed");
    RecordingTransport transport = new RecordingTransport();
    transport.reply.completeExceptionally(cause);
    IdeCommandCaller caller = new IdeCommandCaller(transport, TIMEOUT);

    IdeCommandCaller.IdeOutcomeUnknownException error =
        assertThrows(
            IdeCommandCaller.IdeOutcomeUnknownException.class, () -> caller.call(ENV, listViews()));

    assertEquals("environment IDE command failed without a confirmed outcome", error.getMessage());
    assertEquals(cause, error.getCause());
    assertEquals(0, transport.abandoned);
  }

  @Test
  void interruptedWaitRestoresTheFlagAbandonsAndReportsUnknown() {
    RecordingTransport transport = new RecordingTransport();
    IdeCommandCaller caller = new IdeCommandCaller(transport, Duration.ofSeconds(5));
    Thread.currentThread().interrupt();
    try {
      IdeCommandCaller.IdeOutcomeUnknownException error =
          assertThrows(
              IdeCommandCaller.IdeOutcomeUnknownException.class,
              () -> caller.call(ENV, listViews()));
      assertEquals("environment IDE command was interrupted", error.getMessage());
      assertEquals(1, transport.abandoned);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void ordinaryCloseWaitsWithoutCommandTimeout() {
    RecordingTransport transport = new RecordingTransport();
    transport.reply.complete(IdeResult.newBuilder().setOk(true).build());
    IdeCommand close =
        IdeCommand.newBuilder().setAction(IdeAction.CLOSE_VIEW).setDiscard(false).build();

    IdeCall call = new IdeCommandCaller(transport, Duration.ofNanos(1)).call(ENV, close);

    assertTrue(call.reply().getOk());
    assertEquals(transport.identity, call.identity());
  }

  private static IdeCommand listViews() {
    return IdeCommand.newBuilder().setAction(IdeAction.LIST_VIEWS).build();
  }

  private static class RecordingTransport extends IdeCommandTransport {

    private final DaemonConnectionIdentity identity = new DaemonConnectionIdentity(ENV, "daemon");
    private final UUID invocationId = UUID.randomUUID();
    private final CompletableFuture<IdeResult> reply = new CompletableFuture<>();
    private volatile RuntimeException sendFailure;
    private volatile int abandoned;
    private volatile UUID abandonedInvocation;

    private RecordingTransport() {
      super(mock(EnvironmentDaemonServer.class));
    }

    @Override
    public IdeRequest requestIde(EnvironmentId environmentId, IdeCommand command) {
      RuntimeException failure = sendFailure;
      if (failure != null) {
        throw failure;
      }
      return new IdeRequest(identity, invocationId, reply);
    }

    @Override
    public void abandonIde(UUID invocation) {
      abandoned++;
      abandonedInvocation = invocation;
    }
  }
}
