package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.NOW;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.id;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.idText;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.modelSnapshot;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.snapshot;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeResponseMapperTestSupport.toolSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.ThreadTreeNode;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.generation.GenerationStatus;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadTreeNodeDTO;
import fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class HarnessRuntimeResponseMapperThreadTest {

  @Test
  void projectsThreadIdentityAndSettings() {
    // Thread cursor、version、时间与 branch settings 必须来自同一 snapshot。
    HarnessThreadDTO dto =
        HarnessRuntimeResponseMapper.toThreadDto(HarnessRuntimeTestFixtures.idleSnapshot());

    assertEquals(idText(1), dto.getThreadId());
    assertEquals("thread", dto.getName());
    assertEquals(idText(1), dto.getSessionId());
    // 根 Thread 没有执行父关系：DTO 必须显式 null 而不是空串。
    assertNull(dto.getParentThreadId());
    assertEquals(idText(1), dto.getHeadEntryId());
    assertEquals(4L, dto.getNextCommandSequence());
    assertEquals(3L, dto.getVersion());
    // 根 Thread 是自身的执行根并携带自己的 YOLO 开关。
    assertEquals(dto.getThreadId(), dto.getRootThreadId());
    assertTrue(dto.isYoloEnabled());
    assertEquals("default-assistant", dto.getBranchSettings().getAgentName());
    assertEquals("builtin", dto.getBranchSettings().getExecution().getDriver());
    assertEquals(
        "gpt-5",
        dto.getBranchSettings().getExecution().getSettings().get("modelName").stringValue());
    assertEquals(
        "default",
        dto.getBranchSettings().getExecution().getSettings().get("variant").stringValue());
    // Environment 引用投影为 nullable name（该 fixture 未选择环境）。
    assertNull(dto.getBranchSettings().getEnvironmentName());
    assertEquals(NOW, dto.getCreatedAt());
    assertEquals(NOW, dto.getUpdatedAt());
    assertEquals(ThreadExecutionControl.RUNNABLE, dto.getExecutionControl());
  }

  @Test
  void projectsEveryReachableRuntimeStatusFromThreadSnapshot() {
    // 测试意图：对外状态严格收敛为四态（RUNNING / EXIT_PENDING / WAITING / IDLE），与持久 executionControl 正交。
    ThreadSnapshot idle = HarnessRuntimeTestFixtures.idleSnapshot();
    ThreadSnapshot waiting =
        snapshot(
            idle.thread(),
            new EntryPath(List.of(HarnessRuntimeTestFixtures.rootEntry())),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            1,
            false,
            List.of(),
            false);
    ThreadSnapshot running = modelSnapshot(GenerationStatus.RUNNING);
    ThreadSnapshot exitPending =
        snapshot(
            idle.thread(),
            new EntryPath(List.of(HarnessRuntimeTestFixtures.rootEntry())),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(
                new ExecutionExitFact(
                    id(70),
                    idle.thread().id(),
                    id(71),
                    1,
                    id(72),
                    ExecutionExitKind.TOOL,
                    null,
                    null,
                    ExecutionExitState.UNCONFIRMED,
                    NOW,
                    null,
                    null)),
            false);

    Map<ThreadRuntimeStatus, ThreadSnapshot> cases = new LinkedHashMap<>();
    cases.put(ThreadRuntimeStatus.IDLE, idle);
    cases.put(ThreadRuntimeStatus.EXIT_PENDING, exitPending);
    cases.put(ThreadRuntimeStatus.WAITING, waiting);
    cases.put(ThreadRuntimeStatus.RUNNING, running);
    assertEquals(Set.of(ThreadRuntimeStatus.values()), cases.keySet());

    for (Map.Entry<ThreadRuntimeStatus, ThreadSnapshot> entry : cases.entrySet()) {
      HarnessThreadDTO dto = HarnessRuntimeResponseMapper.toThreadDto(entry.getValue());
      assertEquals(entry.getKey(), dto.getStatus());
      assertEquals(ThreadExecutionControl.RUNNABLE, dto.getExecutionControl());
    }

    HarnessThreadDTO terminalTool =
        HarnessRuntimeResponseMapper.toThreadDto(toolSnapshot(ToolInvocationStatus.FAILED));
    assertEquals(ThreadRuntimeStatus.RUNNING, terminalTool.getStatus());
    assertEquals(ThreadExecutionControl.RUNNABLE, terminalTool.getExecutionControl());
  }

  @Test
  void projectsIdleParentAndQueuedThreadStatusFromSnapshot() {
    // 无本地工作和未完成直接委派时为 IDLE；执行父关系本身不代表仍有任务。
    ThreadSnapshot parent = HarnessRuntimeTestFixtures.idleParentSnapshot(id(2));
    HarnessThreadDTO parentDto = HarnessRuntimeResponseMapper.toThreadDto(parent);
    assertEquals(ThreadRuntimeStatus.IDLE, parentDto.getStatus());
    assertEquals(ThreadExecutionControl.RUNNABLE, parentDto.getExecutionControl());
    // 同一快照必须同时暴露不可变执行父关系，供前端展示执行树而不混入 Session 对话历史。
    assertEquals(idText(2), parentDto.getParentThreadId());

    // 已有接受但尚未被消费的命令同样是"工作尚未结束"：待启动 RUNNING，且不是 IDLE。
    ThreadSnapshot queued = HarnessRuntimeTestFixtures.queuedSnapshot();
    HarnessThreadDTO queuedDto = HarnessRuntimeResponseMapper.toThreadDto(queued);
    assertEquals(ThreadRuntimeStatus.RUNNING, queuedDto.getStatus());
    assertEquals(ThreadExecutionControl.RUNNABLE, queuedDto.getExecutionControl());
    // 根 Thread 的父关系必须显式 null（不是空串），前端据此判断执行树根。
    assertNull(queuedDto.getParentThreadId());
  }

  /** 关系树投影沿用快照状态与计数，不向未结束节点伪造成功。 */
  @Test
  void projectsTreeNodesFromRuntimeSnapshots() {
    ThreadTreeNode parent =
        new ThreadTreeNode(
            HarnessRuntimeTestFixtures.idleParentSnapshot(id(2)).thread(),
            new BranchSettings(
                "demo-agent", BuiltinExecution.of(new ModelSelection("gpt-5", "default")), null),
            ThreadRuntimeStatus.IDLE,
            null,
            new EntryPathMetrics(0, 0));
    ThreadTreeNode continuing =
        new ThreadTreeNode(
            HarnessRuntimeTestFixtures.continuationPendingSnapshot(id(3)).thread(),
            new BranchSettings(
                "demo-agent", BuiltinExecution.of(new ModelSelection("gpt-5", "default")), null),
            ThreadRuntimeStatus.RUNNING,
            null,
            new EntryPathMetrics(1, 0));
    List<HarnessThreadTreeNodeDTO> nodes =
        HarnessRuntimeResponseMapper.toThreadTreeDtos(List.of(parent, continuing));

    assertEquals(2, nodes.size());
    HarnessThreadTreeNodeDTO node = nodes.get(0);
    assertEquals(id(1).toString(), node.getThreadId());
    assertEquals(id(2).toString(), node.getParentThreadId());
    assertEquals(parent.thread().name(), node.getName());
    assertEquals(parent.branchSettings().agentName(), node.getAgentName());
    assertEquals("builtin", node.getExecution().getDriver());
    assertEquals("gpt-5", node.getExecution().getSettings().get("modelName").stringValue());
    assertEquals("default", node.getExecution().getSettings().get("variant").stringValue());
    assertEquals(ThreadRuntimeStatus.IDLE, node.getStatus());
    assertEquals(ThreadExecutionControl.RUNNABLE, node.getExecutionControl());
    assertEquals(0, node.getTurnCount());
    assertEquals(0, node.getToolCallCount());
    assertNull(node.getOutcome());
    assertEquals(parent.thread().updatedAt(), node.getUpdatedAt());
    assertEquals(ThreadRuntimeStatus.RUNNING, nodes.get(1).getStatus());
    assertEquals(ThreadExecutionControl.RUNNABLE, nodes.get(1).getExecutionControl());
    assertEquals(1, nodes.get(1).getTurnCount());
    assertNull(nodes.get(1).getOutcome());
    assertEquals(continuing.thread().updatedAt(), nodes.get(1).getUpdatedAt());
  }

  @Test
  void treeNodesProjectExitPendingWithoutFabricatingOutcome() {
    // 测试意图：执行树节点能把未确认退出投影为 EXIT_PENDING，非 IDLE 不伪造 outcome。
    ThreadTreeNode exitPending =
        new ThreadTreeNode(
            HarnessRuntimeTestFixtures.completedTurnSnapshot(id(3)).thread(),
            new BranchSettings(
                "demo-agent", BuiltinExecution.of(new ModelSelection("gpt-5", "default")), null),
            ThreadRuntimeStatus.EXIT_PENDING,
            null,
            new EntryPathMetrics(1, 0));
    HarnessThreadTreeNodeDTO node =
        HarnessRuntimeResponseMapper.toThreadTreeDtos(List.of(exitPending)).getFirst();
    assertEquals(ThreadRuntimeStatus.EXIT_PENDING, node.getStatus());
    assertNull(node.getOutcome());
  }

  @Test
  void treeNodeWithWaitingLocalTurnEndDoesNotExposeCompletedOutcome() {
    // 测试意图：tree 非 IDLE outcome 不得声明 COMPLETED；子任务未完成时 WAITING 且 outcome 必须为 null。
    ThreadTreeNode waitingWithTurnEnd =
        new ThreadTreeNode(
            HarnessRuntimeTestFixtures.completedTurnSnapshot(id(3)).thread(),
            new BranchSettings(
                "demo-agent", BuiltinExecution.of(new ModelSelection("gpt-5", "default")), null),
            ThreadRuntimeStatus.WAITING,
            null,
            new EntryPathMetrics(1, 0));
    List<HarnessThreadTreeNodeDTO> nodes =
        HarnessRuntimeResponseMapper.toThreadTreeDtos(List.of(waitingWithTurnEnd));
    assertEquals(ThreadRuntimeStatus.WAITING, nodes.getFirst().getStatus());
    assertNull(nodes.getFirst().getOutcome());
  }
}
