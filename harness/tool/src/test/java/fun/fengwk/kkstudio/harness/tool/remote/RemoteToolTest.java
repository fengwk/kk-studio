package fun.fengwk.kkstudio.harness.tool.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class RemoteToolTest {

  private static final EnvironmentBinding ENVIRONMENT =
      new EnvironmentBinding(new EnvironmentName("env-a"), ".");

  @Test
  void executeDelegatesInvokeAndMapsCancel() {
    AtomicReference<ToolExecutionListener> listenerRef = new AtomicReference<>();
    RecordingTransport transport = new RecordingTransport(listenerRef);
    ToolDescriptor descriptor = descriptor();
    RemoteTool tool = new RemoteTool(descriptor, ENVIRONMENT, transport);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("c1", "echo", "{}"),
            Duration.ofSeconds(5),
            new ToolExecutionContext(new UUID(0L, 9L), new UUID(0L, 3L)));
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle = tool.execute(request, listener);
    assertEquals(ENVIRONMENT, transport.environment);
    assertSame(request, transport.request);
    handle.cancel();
    assertTrue(transport.cancelled);

    listenerRef
        .get()
        .onComplete(new ToolResult("c1", List.of(new TextToolContent("ok")), false, "{}"));
    assertEquals("ok", ((TextToolContent) listener.completed.contents().get(0)).text());
  }

  /**
   * Capability 重构不可退化的 fitness gate：RemoteTool 必须复用同一 listener，按 transport 到达顺序透传多个 partial 和
   * terminal。
   */
  @Test
  void executeForwardsOrderedStreamingCallbacksToSameListener() {
    AtomicReference<ToolExecutionListener> listenerRef = new AtomicReference<>();
    RecordingTransport transport = new RecordingTransport(listenerRef, true);
    ToolDescriptor descriptor = descriptor();
    RemoteTool tool = new RemoteTool(descriptor, ENVIRONMENT, transport);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("c1", "echo", "{}"),
            Duration.ofSeconds(5),
            new ToolExecutionContext(new UUID(0L, 9L), new UUID(0L, 3L)));
    RecordingListener listener = new RecordingListener();

    tool.execute(request, listener);

    assertSame(listener, listenerRef.get());
    assertEquals(List.of("partial1", "partial2", "complete"), listener.events);
    assertEquals("complete", ((TextToolContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void unavailableAndUncertainExceptionsPropagate() {
    ToolDescriptor descriptor = descriptor();
    RemoteTool unavailable =
        new RemoteTool(
            descriptor,
            ENVIRONMENT,
            (binding, request, listener) -> {
              throw new RemoteToolUnavailableException("offline");
            });
    RemoteTool uncertain =
        new RemoteTool(
            descriptor,
            ENVIRONMENT,
            (binding, request, listener) -> {
              throw new RemoteToolSendUncertainException("maybe");
            });
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("c1", "echo", "{}"),
            Duration.ofSeconds(1),
            new ToolExecutionContext(new UUID(0L, 1L), new UUID(0L, 1L)));
    assertThrows(
        RemoteToolUnavailableException.class,
        () -> unavailable.execute(request, new RecordingListener()));
    assertThrows(
        RemoteToolSendUncertainException.class,
        () -> uncertain.execute(request, new RecordingListener()));
  }

  private static ToolDescriptor descriptor() {
    return new ToolDescriptor(
        "echo",
        "1",
        ToolType.ENVIRONMENT,
        "echo",
        "echo",
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ZERO);
  }

  private static final class RecordingTransport implements RemoteToolTransport {
    private final AtomicReference<ToolExecutionListener> listenerRef;
    private final boolean streamOnInvoke;
    private EnvironmentBinding environment;
    private ToolExecutionRequest request;
    private boolean cancelled;

    private RecordingTransport(AtomicReference<ToolExecutionListener> listenerRef) {
      this(listenerRef, false);
    }

    private RecordingTransport(
        AtomicReference<ToolExecutionListener> listenerRef, boolean streamOnInvoke) {
      this.listenerRef = listenerRef;
      this.streamOnInvoke = streamOnInvoke;
    }

    @Override
    public ToolExecutionHandle invoke(
        EnvironmentBinding environment,
        ToolExecutionRequest request,
        ToolExecutionListener listener) {
      this.environment = environment;
      this.request = request;
      listenerRef.set(listener);
      if (streamOnInvoke) {
        listener.onPartial(
            new ToolResult("c1", List.of(new TextToolContent("partial1")), false, "{}"));
        listener.onPartial(
            new ToolResult("c1", List.of(new TextToolContent("partial2")), false, "{}"));
        listener.onComplete(
            new ToolResult("c1", List.of(new TextToolContent("complete")), false, "{}"));
      }
      return new ToolExecutionHandle() {
        @Override
        public void cancel() {
          cancelled = true;
        }

        @Override
        public boolean isCancelled() {
          return cancelled;
        }
      };
    }
  }

  private static final class RecordingListener implements ToolExecutionListener {
    private final List<String> events = new ArrayList<>();
    private ToolResult completed;

    @Override
    public void onPartial(ToolResult partial) {
      events.add(((TextToolContent) partial.contents().get(0)).text());
    }

    @Override
    public void onComplete(ToolResult result) {
      events.add("complete");
      completed = result;
    }

    @Override
    public void onError(Throwable error) {}
  }
}
