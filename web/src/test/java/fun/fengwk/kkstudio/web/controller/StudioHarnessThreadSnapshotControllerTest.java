package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationStatus;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntrySlice;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.interaction.EnvironmentToolWait;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Thread 快照读取端点：完整快照、增量快照、费用投影与环境等待叠加。 */
class StudioHarnessThreadSnapshotControllerTest extends HarnessThreadControllerTestSupport {

  @Test
  void missingSnapshotReturnsNotFoundProblem() throws Exception {
    // 真实 Runtime 的 Domain not-found 返回稳定类别和安全详情。
    when(runtime.getThreadSnapshot(id(1)))
        .thenThrow(DomainException.notFound("thread", "thread missing"));
    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.detail").value("thread missing"));
  }

  /** 无本地工作和未完成直接委派时透传 IDLE/RUNNABLE，并暴露执行父关系；路由不另行推导状态。 */
  @Test
  void snapshotProjectsIdleParentStatusAndParentThreadId() throws Exception {
    when(runtime.getThreadSnapshot(id(1)))
        .thenReturn(HarnessRuntimeTestFixtures.idleParentSnapshot(id(2)));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.thread.status").value("IDLE"))
        .andExpect(jsonPath("$.thread.executionControl").value("RUNNABLE"))
        .andExpect(jsonPath("$.thread.processing").doesNotExist())
        .andExpect(jsonPath("$.thread.parentThreadId").value(idText(2)));
  }

  /** 意图：快照的每个 Entry 都带读取时费用投影，未计价 Entry 显式为 null；投影只发生一次（不逐消息请求）。 */
  @Test
  void snapshotCarriesReadTimeUsageCostPerEntry() throws Exception {
    // ROOT -> TURN_START -> USER -> ASSISTANT -> TURN_END：费用只落在记录了用量的 ASSISTANT Entry 上。
    when(runtime.getThreadSnapshot(id(1)))
        .thenReturn(HarnessRuntimeTestFixtures.continuationPendingSnapshot(id(1)));
    HarnessUsageCostDTO cost = new HarnessUsageCostDTO();
    cost.setCurrency("USD");
    cost.setAmount("0.500000000000");
    when(usageCostProjectionService.project(any())).thenReturn(Map.of(id(4), cost));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].usageCost").value(nullValue()))
        .andExpect(jsonPath("$.entries[3].entryId").value(idText(4)))
        .andExpect(jsonPath("$.entries[3].usageCost.currency").value("USD"))
        .andExpect(jsonPath("$.entries[3].usageCost.amount").value("0.500000000000"));

    // 一次快照只投影一次，绝不逐消息请求。
    verify(usageCostProjectionService, times(1)).project(any());
  }

  /** sidecar 一次批量回填所有调用；默认 HTTP mapper 的 ISO Instant 语义不需字段专属转换。 */
  @Test
  void snapshotBatchFillsEnvironmentNamesAndDeadlines() throws Exception {
    GenerationProjection generation =
        new GenerationProjection(
            id(10),
            id(2),
            id(3),
            GenerationStatus.SUCCEEDED,
            1,
            0L,
            List.of(new TextMessageContent("answer")),
            null,
            id(4),
            HarnessRuntimeTestFixtures.NOW,
            HarnessRuntimeTestFixtures.NOW.plusSeconds(1),
            List.of());
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.turnStartEntry(id(1)),
                HarnessRuntimeTestFixtures.userMessageEntry(),
                HarnessRuntimeTestFixtures.assistantEntry()));
    ThreadSnapshot snapshot =
        HarnessRuntimeTestFixtures.snapshot(
            HarnessRuntimeTestFixtures.activeThread(id(1), id(4)),
            path,
            List.of(),
            generation,
            List.of(
                HarnessRuntimeTestFixtures.waitingApprovalTool()
                    .decideApproval(
                        ToolApprovalDecision.ALLOWED,
                        id(300),
                        "test",
                        null,
                        Instant.parse("2026-10-08T10:00:00Z"),
                        Instant.parse("2026-10-08T10:00:00Z"))),
            ThreadLiveState.TOOL_ACTIVE,
            List.of(),
            0,
            false,
            List.of(),
            false);
    Instant deadline = Instant.parse("2026-10-08T12:00:00Z");
    when(runtime.getThreadSnapshot(id(1))).thenReturn(snapshot);
    when(runtime.listEnvironmentToolWaits(any()))
        .thenReturn(
            List.of(
                new EnvironmentToolWait(
                    id(100), EnvironmentId.of(id(200)), true, false, deadline)));
    when(environmentService.requireName(EnvironmentId.of(id(200)))).thenReturn("archlinux");
    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.toolInvocations[0].requiredEnvironmentId").value(idText(200)))
        .andExpect(jsonPath("$.toolInvocations[0].requiredEnvironmentName").value("archlinux"))
        // 全局 Instant wire 精度由基础时间切片统一；这里只断言字段存在且为数值。
        .andExpect(jsonPath("$.toolInvocations[0].environmentWaitFreshnessAt").isNumber());
    verify(runtime, times(1)).listEnvironmentToolWaits(Set.of(id(100)));
  }

  /** 意图：完整快照显式返回 baseEntryId=null，且不调用完整压缩判断。 */
  @Test
  void snapshotExposesNullBaseEntryIdWithoutCompactionAvailability() throws Exception {
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("3"))
        .andExpect(jsonPath("$.thread.threadId").value(idText(1)))
        .andExpect(jsonPath("$.baseEntryId").value(nullValue()));

    verify(runtime, never()).manualCompactionAvailability(any());
  }

  @Test
  void getSnapshotWithNonCanonicalAfterEntryIdRejectsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "?afterEntryId=not-a-valid-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  @Test
  void getSnapshotWithCursorAtHeadReturnsEmptyDeltaWithBaseAsHead() throws Exception {
    UUID threadId = id(1);
    UUID headId = id(10);
    Entry root = HarnessRuntimeTestFixtures.rootEntry();
    Entry head =
        new Entry(
            headId,
            id(1),
            root.id(),
            new TurnStartPayload(
                TurnStartReason.INPUT, HarnessRuntimeTestFixtures.settings(), threadId),
            HarnessRuntimeTestFixtures.NOW);
    EntryCurrentState current =
        new EntryCurrentState(head, head, null, null, HarnessRuntimeTestFixtures.settings());
    EntrySlice emptySlice = new EntrySlice(headId, List.of());
    ThreadSnapshot snapshot =
        HarnessRuntimeTestFixtures.snapshot(
            HarnessRuntimeTestFixtures.activeThread(threadId, headId),
            current,
            emptySlice,
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    when(runtime.getThreadSnapshot(threadId, headId)).thenReturn(snapshot);

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "?afterEntryId=" + headId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseEntryId").value(headId.toString()))
        .andExpect(jsonPath("$.entries").isEmpty());
    verify(runtime).getThreadSnapshot(threadId, headId);
  }

  @Test
  void getSnapshotWithNonAncestorCursorFallsBackToFullSnapshot() throws Exception {
    UUID threadId = id(1);
    UUID nonAncestorId = id(999);
    EntryPath fullPath = new EntryPath(List.of(HarnessRuntimeTestFixtures.rootEntry()));
    ThreadSnapshot snapshot =
        HarnessRuntimeTestFixtures.snapshot(
            HarnessRuntimeTestFixtures.activeThread(threadId, fullPath.head().id()),
            EntryCurrentState.from(fullPath),
            new EntrySlice(null, fullPath.entries()),
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    when(runtime.getThreadSnapshot(threadId, nonAncestorId)).thenReturn(snapshot);

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "?afterEntryId=" + nonAncestorId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseEntryId").value(nullValue()))
        .andExpect(jsonPath("$.entries.length()").value(1));
    verify(runtime).getThreadSnapshot(threadId, nonAncestorId);
  }

  @Test
  void getSnapshotProjectsUsageCostOnlyForEntriesInSlice() throws Exception {
    UUID threadId = id(1);
    UUID cursorId = id(5);
    UUID newEntryId = id(6);
    Entry root = HarnessRuntimeTestFixtures.rootEntry();
    Entry entry1 =
        new Entry(
            cursorId,
            id(1),
            root.id(),
            new TurnStartPayload(
                TurnStartReason.INPUT, HarnessRuntimeTestFixtures.settings(), threadId),
            HarnessRuntimeTestFixtures.NOW);
    Entry entry2 =
        new Entry(
            newEntryId,
            id(1),
            cursorId,
            HarnessRuntimeTestFixtures.assistantEntry().payload(),
            HarnessRuntimeTestFixtures.NOW);
    EntryCurrentState current =
        new EntryCurrentState(entry2, entry2, null, null, HarnessRuntimeTestFixtures.settings());
    EntrySlice deltaSlice = new EntrySlice(cursorId, List.of(entry2));
    ThreadSnapshot snapshot =
        HarnessRuntimeTestFixtures.snapshot(
            HarnessRuntimeTestFixtures.activeThread(threadId, newEntryId),
            current,
            deltaSlice,
            List.of(),
            null,
            List.of(),
            ThreadLiveState.IDLE_OR_HISTORICAL,
            List.of(),
            0,
            false,
            List.of(),
            false);
    when(runtime.getThreadSnapshot(threadId, cursorId)).thenReturn(snapshot);

    HarnessUsageCostDTO cost = new HarnessUsageCostDTO();
    cost.setCurrency("USD");
    cost.setAmount("0.420000000000");
    when(usageCostProjectionService.project(List.of(entry2))).thenReturn(Map.of(newEntryId, cost));

    mockMvc
        .perform(get("/api/harness/threads/" + idText(1) + "?afterEntryId=" + cursorId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseEntryId").value(cursorId.toString()))
        .andExpect(jsonPath("$.entries.length()").value(1))
        .andExpect(jsonPath("$.entries[0].entryId").value(newEntryId.toString()))
        .andExpect(jsonPath("$.entries[0].usageCost.currency").value("USD"))
        .andExpect(jsonPath("$.entries[0].usageCost.amount").value("0.420000000000"));
    verify(usageCostProjectionService).project(List.of(entry2));
    verify(usageCostProjectionService, never())
        .project(argThat((List<Entry> list) -> list.contains(root) || list.contains(entry1)));
  }
}
