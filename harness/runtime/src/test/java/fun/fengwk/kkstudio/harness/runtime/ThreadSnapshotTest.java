package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** {@link ThreadSnapshot#runtimeStatus()} 统一细粒度运行时状态投影测试。 */
class ThreadSnapshotTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final UUID SESSION_ID = UUID.randomUUID();
  private static final UUID THREAD_ID = UUID.randomUUID();
  private static final UUID HEAD_ENTRY_ID = UUID.randomUUID();

  @Test
  void waitingChildrenLifecycleMapsToWaitingChildrenStatus() {
    // 测试意图：验证当生命周期为 WAITING_CHILDREN 时，无论本地快照字段如何均映射为 WAITING_CHILDREN。
    ThreadState thread = threadState(ThreadLifecycleStatus.WAITING_CHILDREN);
    ThreadSnapshot snapshot =
        new ThreadSnapshot(thread, mock(EntryPath.class), List.of(), null, List.of(), List.of());
    assertEquals(ThreadRuntimeStatus.WAITING_CHILDREN, snapshot.runtimeStatus());
  }

  @Test
  void idleLifecycleMapsToIdleStatus() {
    // 测试意图：验证当生命周期为 IDLE 时，统一映射为 IDLE 运行时状态。
    ThreadState thread = threadState(ThreadLifecycleStatus.IDLE);
    ThreadSnapshot snapshot =
        new ThreadSnapshot(thread, mock(EntryPath.class), List.of(), null, List.of(), List.of());
    assertEquals(ThreadRuntimeStatus.IDLE, snapshot.runtimeStatus());
  }

  @Test
  void activeLifecycleWithModelMapsToModelStatus() {
    // 测试意图：验证 ACTIVE 生命周期下，若 model 非空且 tools 为空，按 model 状态映射到 MODEL_READY/DISPATCHING/RUNNING。
    ThreadState thread = threadState(ThreadLifecycleStatus.ACTIVE);
    for (ModelInvocationStatus status :
        List.of(
            ModelInvocationStatus.READY,
            ModelInvocationStatus.DISPATCHING,
            ModelInvocationStatus.RUNNING)) {
      ModelInvocation model = model(status);
      ThreadSnapshot snapshot =
          new ThreadSnapshot(thread, mock(EntryPath.class), List.of(), model, List.of(), List.of());
      ThreadRuntimeStatus expected =
          switch (status) {
            case READY -> ThreadRuntimeStatus.MODEL_READY;
            case DISPATCHING -> ThreadRuntimeStatus.MODEL_DISPATCHING;
            case RUNNING -> ThreadRuntimeStatus.MODEL_RUNNING;
            default -> throw new AssertionError();
          };
      assertEquals(expected, snapshot.runtimeStatus());
    }
  }

  @Test
  void activeLifecycleWithToolsMapsToToolStatusByPriority() {
    // 测试意图：验证 ACTIVE 生命周期下，toolSiblings 非空时按照 WAITING_APPROVAL > RUNNING > DISPATCHING > READY
    // 映射。
    ThreadState thread = threadState(ThreadLifecycleStatus.ACTIVE);
    ModelInvocation parentModel = model(ModelInvocationStatus.SUCCEEDED);

    ThreadSnapshot waitingApproval =
        new ThreadSnapshot(
            thread,
            mock(EntryPath.class),
            List.of(),
            parentModel,
            toolInvocations(
                ToolInvocationStatus.READY,
                ToolInvocationStatus.DISPATCHING,
                ToolInvocationStatus.RUNNING,
                ToolInvocationStatus.WAITING_APPROVAL),
            List.of());
    assertEquals(ThreadRuntimeStatus.TOOL_WAITING_APPROVAL, waitingApproval.runtimeStatus());

    ThreadSnapshot running =
        new ThreadSnapshot(
            thread,
            mock(EntryPath.class),
            List.of(),
            parentModel,
            toolInvocations(
                ToolInvocationStatus.READY,
                ToolInvocationStatus.DISPATCHING,
                ToolInvocationStatus.RUNNING),
            List.of());
    assertEquals(ThreadRuntimeStatus.TOOL_RUNNING, running.runtimeStatus());

    ThreadSnapshot dispatching =
        new ThreadSnapshot(
            thread,
            mock(EntryPath.class),
            List.of(),
            parentModel,
            toolInvocations(ToolInvocationStatus.READY, ToolInvocationStatus.DISPATCHING),
            List.of());
    assertEquals(ThreadRuntimeStatus.TOOL_DISPATCHING, dispatching.runtimeStatus());

    ThreadSnapshot ready =
        new ThreadSnapshot(
            thread,
            mock(EntryPath.class),
            List.of(),
            parentModel,
            toolInvocations(ToolInvocationStatus.READY),
            List.of());
    assertEquals(ThreadRuntimeStatus.TOOL_READY, ready.runtimeStatus());
  }

  @Test
  void activeLifecycleWithQueuedOnlyMapsToQueuedStatus() {
    // 测试意图：验证 ACTIVE 生命周期下，若无活跃模型或工具，映射为 QUEUED 状态（等待启动新 turn）。
    ThreadState thread = threadState(ThreadLifecycleStatus.ACTIVE);
    ThreadSnapshot snapshotWithoutCommands =
        new ThreadSnapshot(thread, mock(EntryPath.class), List.of(), null, List.of(), List.of());
    assertEquals(ThreadRuntimeStatus.QUEUED, snapshotWithoutCommands.runtimeStatus());

    ThreadSnapshot snapshotWithCommands =
        new ThreadSnapshot(
            thread,
            mock(EntryPath.class),
            List.of(mock(ThreadCommand.class)),
            null,
            List.of(),
            List.of());
    assertEquals(ThreadRuntimeStatus.QUEUED, snapshotWithCommands.runtimeStatus());
  }

  @Test
  void activeLifecycleRejectsInvalidShapes() {
    // 测试意图：验证 ACTIVE 生命周期下，非法 active 形状（终态 Model 或全终态 Tools）fail-closed 抛出
    // IllegalStateException。
    ThreadState thread = threadState(ThreadLifecycleStatus.ACTIVE);

    for (ModelInvocationStatus status :
        List.of(
            ModelInvocationStatus.SUCCEEDED,
            ModelInvocationStatus.FAILED,
            ModelInvocationStatus.CANCELLED,
            ModelInvocationStatus.UNKNOWN)) {
      ThreadSnapshot snapshot =
          new ThreadSnapshot(
              thread, mock(EntryPath.class), List.of(), model(status), List.of(), List.of());
      assertThrows(IllegalStateException.class, snapshot::runtimeStatus);
    }

    ThreadSnapshot terminalToolsSnapshot =
        new ThreadSnapshot(
            thread,
            mock(EntryPath.class),
            List.of(),
            null,
            toolInvocations(
                ToolInvocationStatus.SUCCEEDED,
                ToolInvocationStatus.FAILED,
                ToolInvocationStatus.CANCELLED,
                ToolInvocationStatus.UNKNOWN),
            List.of());
    assertThrows(IllegalStateException.class, terminalToolsSnapshot::runtimeStatus);
  }

  private static ThreadState threadState(ThreadLifecycleStatus status) {
    return new ThreadState(
        THREAD_ID,
        SESSION_ID,
        null,
        HEAD_ENTRY_ID,
        CREATION_REQUEST_HASH,
        "main",
        true,
        status,
        1L,
        0L,
        CREATED,
        CREATED);
  }

  private static ModelInvocation model(ModelInvocationStatus status) {
    ModelInvocation model = mock(ModelInvocation.class);
    when(model.status()).thenReturn(status);
    return model;
  }

  private static List<ToolInvocation> toolInvocations(ToolInvocationStatus... statuses) {
    return Arrays.stream(statuses)
        .map(
            status -> {
              ToolInvocation invocation = mock(ToolInvocation.class);
              when(invocation.status()).thenReturn(status);
              return invocation;
            })
        .toList();
  }
}
