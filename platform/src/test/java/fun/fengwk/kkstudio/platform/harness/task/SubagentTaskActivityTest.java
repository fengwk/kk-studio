package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.platform.harness.task.repo.SubagentTaskRepository;

import java.util.List;
import java.util.UUID;

/**
 * 验证"子树是否仍构成活动"的对外聚合语义。
 *
 * <p>测试意图：异步 task 的父 Thread 在等待子结果时自身静止，但本次工作并未结束，因此对外必须仍呈现处理中；同时**被显式停止的 Thread
 * 不会被唤醒**，其挂起的终态结果不得让 owner/Issue 永远停在 processing。两条约束的分界必须逐条钉死：
 *
 * <ul>
 *   <li>子树内还有 {@code OPEN} 执行时一律构成活动——运行中的执行是真实在跑的工作，祖先（含查询对象自身）停止也不能掩盖它；
 *   <li>{@code SETTLED} 待交付只在相应父未停止时构成活动：父已停止的记录不会被交付，纯挂起结果既不唤醒父也不应算作处理中。
 * </ul>
 */
class SubagentTaskActivityTest {

  private static final UUID ROOT_THREAD_ID = new UUID(0L, 1L);
  private static final UUID PARENT_THREAD_ID = new UUID(0L, 2L);
  private static final BranchSettings SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"), null);

  private SubagentTaskRepository repository;
  private HarnessRuntime runtime;
  private SubagentTaskActivity activity;

  @BeforeEach
  void setUp() {
    repository = mock(SubagentTaskRepository.class);
    runtime = mock(HarnessRuntime.class);
    activity = new SubagentTaskActivity(repository, () -> runtime);
  }

  @Test
  void subtreeWithoutUndeliveredDelegationIsNotActivity() {
    // 测试意图：子树内既没有 OPEN 执行也没有待交付终态时不产生活动，且不读取任何 Thread 快照（常见路径不付出代价）。
    when(repository.hasOpenInSubtree(ROOT_THREAD_ID)).thenReturn(false);
    when(repository.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID)).thenReturn(List.of());

    assertFalse(activity.hasPendingDelegatedWork(ROOT_THREAD_ID));
    verify(runtime, never()).getThreadSnapshot(any());
  }

  @Test
  void openExecutionUnderLiveParentIsActivity() {
    // 测试意图：子树里存在正在执行的委派时该 Thread（含 owner/Issue 视图）必须保持处理中；判定只看持久记录，不需要读快照。
    when(repository.hasOpenInSubtree(ROOT_THREAD_ID)).thenReturn(true);

    assertTrue(activity.hasPendingDelegatedWork(ROOT_THREAD_ID));
    verify(runtime, never()).getThreadSnapshot(any());
  }

  @Test
  void openExecutionIsActivityEvenWhenQueryThreadItselfIsStopped() {
    // 测试意图：查询对象自身已停止时不能把后代正在执行的委派当成"没有活动"——停止尚未确认前它仍在跑，
    // 会被误判成静止就会让退出/推进门禁放过一棵还在运行的子树。
    when(repository.hasOpenInSubtree(PARENT_THREAD_ID)).thenReturn(true);

    assertTrue(activity.hasPendingDelegatedWork(PARENT_THREAD_ID));
    verify(runtime, never()).getThreadSnapshot(any());
  }

  @Test
  void settledPendingUnderLiveParentIsActivity() {
    // 测试意图：子树里存在已结清但尚未交付的结果、且其父仍会接收交付时，该 Thread 必须保持处理中（父会被唤醒并处理它）。
    when(repository.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID))
        .thenReturn(List.of(PARENT_THREAD_ID));
    ThreadSnapshot live = liveSnapshot();
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(live);

    assertTrue(activity.hasPendingDelegatedWork(ROOT_THREAD_ID));
  }

  @Test
  void settledPendingUnderStoppedParentIsNotActivity() {
    // 测试意图：父 Thread 显式停止后其待交付结果不会唤醒父，因此不计入活动——否则 owner/Issue 会永远停在 processing。
    when(repository.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID))
        .thenReturn(List.of(PARENT_THREAD_ID));
    Entry stoppedHead = stoppedHead();
    ThreadSnapshot stopped = snapshot(stoppedHead);
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID)).thenReturn(stopped);

    assertFalse(activity.hasPendingDelegatedWork(ROOT_THREAD_ID));
  }

  @Test
  void pureSettledHangOnStoppedSelfIsNotActivity() {
    // 测试意图：查询对象自己是停止线程、且子树里只剩它自己名下的挂起终态结果时不是活动：
    // "已停止 + 纯挂起结果"既不唤醒也不 processing（区别于后代仍在执行的情况）。
    when(repository.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID))
        .thenReturn(List.of(ROOT_THREAD_ID));
    Entry stoppedHead = stoppedHead();
    ThreadSnapshot stopped = snapshot(stoppedHead);
    when(runtime.getThreadSnapshot(ROOT_THREAD_ID)).thenReturn(stopped);

    assertFalse(activity.hasPendingDelegatedWork(ROOT_THREAD_ID));
  }

  @Test
  void missingParentThreadIsNotActivity() {
    // 测试意图：待交付记录的父 Thread 已不存在时记录随父级联删除，既不构成活动也不应让读取失败冒泡成业务错误。
    when(repository.listSettledParentThreadIdsInSubtree(ROOT_THREAD_ID))
        .thenReturn(List.of(PARENT_THREAD_ID));
    when(runtime.getThreadSnapshot(PARENT_THREAD_ID))
        .thenThrow(new HarnessRuntimeNotFoundException("gone"));

    assertFalse(activity.hasPendingDelegatedWork(ROOT_THREAD_ID));
  }

  @Test
  void unavailableRuntimeIsNotActivity() {
    // 测试意图：Runtime 不可用时保守地不宣称活动，避免读取通道缺失把整个 Issue 卡在推进中。
    SubagentTaskActivity offline = new SubagentTaskActivity(repository, () -> null);

    assertFalse(offline.hasPendingDelegatedWork(ROOT_THREAD_ID));
    verify(repository, never()).hasOpenInSubtree(any());
  }

  @Test
  void nullThreadIdIsRejected() {
    assertThrows(NullPointerException.class, () -> activity.hasPendingDelegatedWork(null));
  }

  private static ThreadSnapshot snapshot(Entry head) {
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    EntryPath path = mock(EntryPath.class);
    when(snapshot.entryPath()).thenReturn(path);
    when(path.head()).thenReturn(head);
    return snapshot;
  }

  private static ThreadSnapshot liveSnapshot() {
    return snapshot(activeHead());
  }

  /** 非停止态 head：EntryPayload 是 sealed 接口不可 mock，直接用真实 payload 表达"没有 STOPPED 边界"。 */
  private static Entry activeHead() {
    Entry entry = mock(Entry.class);
    TurnStartPayload payload =
        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, ROOT_THREAD_ID);
    when(entry.payload()).thenReturn(payload);
    return entry;
  }

  private static Entry stoppedHead() {
    Entry entry = mock(Entry.class);
    when(entry.payload())
        .thenReturn(
            new TurnEndPayload(
                UUID.randomUUID(),
                TurnEndOutcome.STOPPED,
                false,
                TurnEndReason.USER_STOP,
                UUID.randomUUID()));
    return entry;
  }
}
