package fun.fengwk.kkstudio.harness.tool.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

class RemoteToolTest {

  private static final EnvironmentId ENVIRONMENT_ID =
      new EnvironmentId("123e4567-e89b-12d3-a456-426614174000");

  @Test
  void executeDelegatesInvokeAndMapsCancel() {
    AtomicReference<ToolExecutionListener> listenerRef = new AtomicReference<>();
    RecordingTransport transport = new RecordingTransport(listenerRef);
    ToolDescriptor descriptor = descriptor();
    RemoteTool tool = new RemoteTool(descriptor, ENVIRONMENT_ID, transport);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("c1", "echo", "{}"),
            Duration.ofSeconds(5),
            new ToolExecutionContext(9L, 3L));
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle = tool.execute(request, listener);
    assertEquals(ENVIRONMENT_ID, transport.environmentId);
    assertSame(request, transport.request);
    handle.cancel();
    assertTrue(transport.cancelled);

    listenerRef
        .get()
        .onComplete(new ToolResult("c1", List.of(new TextToolContent("ok")), false, "{}", false));
    assertEquals("ok", ((TextToolContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void unavailableAndUncertainExceptionsPropagate() {
    ToolDescriptor descriptor = descriptor();
    RemoteTool unavailable =
        new RemoteTool(
            descriptor,
            ENVIRONMENT_ID,
            (environmentId, request, listener) -> {
              throw new RemoteToolUnavailableException("offline");
            });
    RemoteTool uncertain =
        new RemoteTool(
            descriptor,
            ENVIRONMENT_ID,
            (environmentId, request, listener) -> {
              throw new RemoteToolSendUncertainException("maybe");
            });
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor,
            new ToolCall("c1", "echo", "{}"),
            Duration.ofSeconds(1),
            new ToolExecutionContext(1L, 1L));
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
    private EnvironmentId environmentId;
    private ToolExecutionRequest request;
    private boolean cancelled;

    private RecordingTransport(AtomicReference<ToolExecutionListener> listenerRef) {
      this.listenerRef = listenerRef;
    }

    @Override
    public ToolExecutionHandle invoke(
        EnvironmentId environmentId, ToolExecutionRequest request, ToolExecutionListener listener) {
      this.environmentId = environmentId;
      this.request = request;
      listenerRef.set(listener);
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
    private ToolResult completed;

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolResult result) {
      completed = result;
    }

    @Override
    public void onError(Throwable error) {}
  }
}
