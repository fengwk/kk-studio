package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.api.ThreadTreeNode;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures;

import java.util.List;

/** Thread 只读查询端点：执行树与手动压缩 availability。 */
class HarnessThreadControllerRuntimeQueryTest extends HarnessThreadControllerTestSupport {

  /** 子节点入口返回真实根和父关系；根与未结束 outcome 必须显式序列化为 null。 */
  @Test
  void treeReturnsMinimalNodesAndExplicitNullableFields() throws Exception {
    when(runtime.getThreadTree(id(1)))
        .thenReturn(
            List.of(
                new ThreadTreeNode(
                    HarnessRuntimeTestFixtures.idleSnapshot(id(2)).thread(),
                    new BranchSettings(
                        "demo-agent",
                        BuiltinExecution.of(new ModelSelection("gpt-5", "default")),
                        null),
                    ThreadRuntimeStatus.IDLE,
                    null,
                    new EntryPathMetrics(0, 0)),
                new ThreadTreeNode(
                    HarnessRuntimeTestFixtures.idleParentSnapshot(id(2)).thread(),
                    new BranchSettings(
                        "demo-agent",
                        BuiltinExecution.of(new ModelSelection("gpt-5", "default")),
                        null),
                    ThreadRuntimeStatus.IDLE,
                    null,
                    new EntryPathMetrics(0, 0))));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].threadId").value(idText(2)))
        .andExpect(jsonPath("$[0].parentThreadId").hasJsonPath())
        .andExpect(jsonPath("$[0].parentThreadId").value(nullValue()))
        .andExpect(jsonPath("$[0].outcome").hasJsonPath())
        .andExpect(jsonPath("$[0].outcome").value(nullValue()))
        .andExpect(jsonPath("$[0].execution.driver").value("builtin"))
        .andExpect(jsonPath("$[0].execution.settings.modelName").value("gpt-5"))
        .andExpect(jsonPath("$[0].turnCount").value(0))
        .andExpect(jsonPath("$[0].toolCallCount").value(0))
        .andExpect(jsonPath("$[0].entries").doesNotExist())
        .andExpect(jsonPath("$[0].queuedCommands").doesNotExist())
        .andExpect(jsonPath("$[1].parentThreadId").value(idText(2)))
        .andExpect(jsonPath("$[1].status").value("IDLE"))
        .andExpect(jsonPath("$[1].executionControl").value("RUNNABLE"))
        .andExpect(jsonPath("$[1].processing").doesNotExist());

    verify(runtime).getThreadTree(id(1));
    verify(runtime, never()).getThreadSnapshot(any());
    verifyNoInteractions(modelRequestDebugService, interactionService);
  }

  /** 非 canonical UUID 在进入 Runtime 前拒绝；未知节点沿用快照的 404 语义。 */
  @Test
  void treeRejectsInvalidUuidAndReturnsNotFoundForMissingThread() throws Exception {
    mockMvc.perform(get("/api/harness/threads/not-a-uuid/tree")).andExpect(status().isBadRequest());
    verifyNoInteractions(runtime);
    when(runtime.getThreadTree(id(999)))
        .thenThrow(DomainException.notFound("thread", "thread " + idText(999) + " does not exist"));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(999) + "/tree"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("thread " + idText(999) + " does not exist"));
  }

  /** 意图：独立 advisory 端点按需返回既有压缩判断，非法 id 为 400、缺失 Thread 为 404。 */
  @Test
  void compactionAvailabilityIsASeparateEndpoint() throws Exception {
    when(runtime.manualCompactionAvailability(id(1)))
        .thenReturn(
            ManualCompactionAvailability.disabled(
                ManualCompactionAvailability.DisabledReason.BELOW_MINIMUM));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "/compaction-availability"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.disabledReason").value("BELOW_MINIMUM"));
    verify(runtime).manualCompactionAvailability(id(1));
    verify(runtime, never()).getThreadSnapshot(any());

    mockMvc
        .perform(get("/api/harness/threads/not-a-uuid/compaction-availability"))
        .andExpect(status().isBadRequest());

    when(runtime.manualCompactionAvailability(id(999)))
        .thenThrow(DomainException.notFound("thread", "thread " + idText(999) + " does not exist"));
    mockMvc
        .perform(get("/api/harness/threads/" + idText(999) + "/compaction-availability"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("thread " + idText(999) + " does not exist"));
  }
}
