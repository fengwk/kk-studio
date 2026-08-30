package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Environment Capability transport port 的事件顺序、取消和异常分类契约测试。 */
class EnvironmentCapabilityTransportTest {

  private static final EnvironmentCapabilityDescriptor DESCRIPTOR =
      new EnvironmentCapabilityDescriptor(
          EnvironmentCapabilityIds.FS_READ,
          "1",
          new InputSchema(null, Map.of("path", new StringSchema(null)), Set.of("path"), false),
          Duration.ofSeconds(10));
  private static final EnvironmentBinding BINDING =
      new EnvironmentBinding(new EnvironmentName("env-a"), "workspace");
  private static final EnvironmentCapabilityExecutionRequest REQUEST =
      new EnvironmentCapabilityExecutionRequest(
          DESCRIPTOR,
          new EnvironmentCapabilityCall("call-1", "{\"path\":\"README.md\"}"),
          Duration.ZERO,
          null);

  /** 多个 partial 必须按 FIFO 顺序透传，FakeTransport 的 terminal fence 丢弃 late event，cancel 必须幂等。 */
  @Test
  void forwardsOrderedPartialsTerminalAndCancel() {
    FakeTransport transport = new FakeTransport(Mode.STREAM);
    RecordingListener listener = new RecordingListener();

    EnvironmentCapabilityExecutionHandle handle = transport.invoke(BINDING, REQUEST, listener);

    assertEquals(List.of("partial-1", "partial-2", "complete"), listener.events);
    assertFalse(listener.error);
    assertTrue(transport.invoked);
    assertFalse(handle.isCancelled());
    handle.cancel();
    handle.cancel();
    assertTrue(handle.isCancelled());
  }

  /** FAILED/CANCELLED 必须通过 listener terminal error 透传为各自明确异常。 */
  @Test
  void forwardsTerminalFailureAndCancellationExceptions() {
    RecordingListener failed = new RecordingListener();
    new FakeTransport(Mode.FAILED).invoke(BINDING, REQUEST, failed);
    assertTrue(failed.error);
    assertInstanceOf(EnvironmentCapabilityFailedException.class, failed.terminal);

    RecordingListener cancelled = new RecordingListener();
    new FakeTransport(Mode.CANCELLED).invoke(BINDING, REQUEST, cancelled);
    assertTrue(cancelled.error);
    assertInstanceOf(EnvironmentCapabilityCancelledException.class, cancelled.terminal);
  }

  /** Busy/Unavailable 保证尚未执行，SendUncertain 则明确表达可能已接受且不可重放。 */
  @Test
  void classifiesStartExceptionsBeforeAnyExecution() {
    assertStartException(Mode.BUSY, EnvironmentCapabilityBusyException.class);
    assertStartException(Mode.UNAVAILABLE, EnvironmentCapabilityUnavailableException.class);
    assertStartException(Mode.UNCERTAIN, EnvironmentCapabilitySendUncertainException.class);
  }

  private static void assertStartException(
      Mode mode, Class<? extends RuntimeException> exceptionType) {
    FakeTransport transport = new FakeTransport(mode);
    RecordingListener listener = new RecordingListener();

    assertThrows(exceptionType, () -> transport.invoke(BINDING, REQUEST, listener));
    assertFalse(transport.invoked);
    assertTrue(listener.events.isEmpty());
    assertFalse(listener.error);
  }

  private enum Mode {
    STREAM,
    FAILED,
    CANCELLED,
    BUSY,
    UNAVAILABLE,
    UNCERTAIN
  }

  private static final class FakeTransport implements EnvironmentCapabilityTransport {
    private final Mode mode;
    private boolean invoked;

    private FakeTransport(Mode mode) {
      this.mode = mode;
    }

    @Override
    public EnvironmentCapabilityExecutionHandle invoke(
        EnvironmentBinding binding,
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      assertEquals(BINDING, binding);
      assertEquals(REQUEST, request);
      if (mode == Mode.BUSY) {
        throw new EnvironmentCapabilityBusyException("busy");
      }
      if (mode == Mode.UNAVAILABLE) {
        throw new EnvironmentCapabilityUnavailableException("unavailable");
      }
      if (mode == Mode.UNCERTAIN) {
        throw new EnvironmentCapabilitySendUncertainException("uncertain");
      }

      invoked = true;
      AtomicBoolean cancelled = new AtomicBoolean();
      EnvironmentCapabilityExecutionListener fencedListener = new TerminalFence(listener);
      if (mode == Mode.STREAM) {
        fencedListener.onPartial(result("partial-1"));
        fencedListener.onPartial(result("partial-2"));
        fencedListener.onComplete(result("complete"));
        fencedListener.onPartial(result("late-partial"));
        fencedListener.onComplete(result("duplicate-complete"));
        fencedListener.onError(new EnvironmentCapabilityFailedException("duplicate-failed"));
      } else if (mode == Mode.FAILED) {
        fencedListener.onError(new EnvironmentCapabilityFailedException("failed"));
      } else {
        fencedListener.onError(new EnvironmentCapabilityCancelledException("cancelled"));
      }
      return new EnvironmentCapabilityExecutionHandle() {
        @Override
        public void cancel() {
          cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
          return cancelled.get();
        }
      };
    }

    /** Fake 内只实现 transport contract 所需的最小 terminal fence；它不替生产 transport 提供实现。 */
    private static final class TerminalFence implements EnvironmentCapabilityExecutionListener {
      private final EnvironmentCapabilityExecutionListener delegate;
      private boolean terminal;

      private TerminalFence(EnvironmentCapabilityExecutionListener delegate) {
        this.delegate = delegate;
      }

      @Override
      public void onPartial(EnvironmentCapabilityResult partial) {
        if (!terminal) {
          delegate.onPartial(partial);
        }
      }

      @Override
      public void onComplete(EnvironmentCapabilityResult result) {
        if (!terminal) {
          terminal = true;
          delegate.onComplete(result);
        }
      }

      @Override
      public void onError(Throwable error) {
        if (!terminal) {
          terminal = true;
          delegate.onError(error);
        }
      }
    }

    private EnvironmentCapabilityResult result(String text) {
      return new EnvironmentCapabilityResult(
          REQUEST.call().id(), List.of(new TextResultContent(text)), false, "{}");
    }
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final List<String> events = new ArrayList<>();
    private boolean error;
    private Throwable terminal;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      events.add(((TextResultContent) partial.contents().getFirst()).text());
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      events.add(((TextResultContent) result.contents().getFirst()).text());
    }

    @Override
    public void onError(Throwable error) {
      this.error = true;
      this.terminal = error;
      events.add("error");
    }
  }
}
