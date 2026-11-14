package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolFileMutationOrder;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool sibling 的 Work 派发：把 READY 调用转成 TOOL Work 请求。
 *
 * <p>同一文件键只派发第一个仍 live 的调用（后续同键调用必须等前序结束），无键调用在进入时即派发。派发只写 TOOL Work，不触碰 ToolInvocation 行，也不改变
 * Thread version。
 */
final class ToolDispatch {

  /** Dispatches unkeyed calls as before and only the first live call for each file key. */
  static boolean dispatchReadyTools(HarnessStore.Transaction tx, List<ToolInvocation> invocations) {
    List<ToolInvocation> ordered = sortedByCallIndex(invocations);
    Map<ToolFileMutationOrder.Key, ToolInvocation> firstByFile = new HashMap<>();
    List<ToolInvocation> unkeyed = new ArrayList<>();
    for (ToolInvocation invocation : ordered) {
      Optional<ToolFileMutationOrder.Key> key = ToolFileMutationOrder.key(invocation);
      if (key.isEmpty()) {
        unkeyed.add(invocation);
      } else if (!invocation.status().isTerminal()) {
        firstByFile.putIfAbsent(key.get(), invocation);
      }
    }
    List<ToolInvocation> dispatchable = new ArrayList<>();
    for (ToolInvocation invocation : unkeyed) {
      if (invocation.status() == ToolInvocationStatus.READY) {
        dispatchable.add(invocation);
      }
    }
    for (ToolInvocation invocation : firstByFile.values()) {
      if (isDispatchableFileMutation(invocation) && !toolWorkExists(tx, invocation)) {
        dispatchable.add(invocation);
      }
    }
    requestAll(tx, dispatchable);
    return !dispatchable.isEmpty();
  }

  /** A THREAD wake rechecks each key's first live slot; unkeyed slots were dispatched on entry. */
  static void dispatchQueuedTools(
      HarnessStore.Transaction tx, ClaimedWork claim, ThreadState thread, Entry assistant) {
    List<ToolInvocation> siblings = tx.tools().lockByAssistantEntry(assistant.id());
    List<ToolInvocation> sameThread =
        siblings.stream()
            .filter(
                invocation ->
                    invocation.threadId().equals(thread.id())
                        && invocation.assistantEntryId().equals(assistant.id()))
            .toList();
    dispatchReadyFileMutations(tx, sameThread);
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.work().complete(claim);
  }

  private static void dispatchReadyFileMutations(
      HarnessStore.Transaction tx, List<ToolInvocation> invocations) {
    Map<ToolFileMutationOrder.Key, ToolInvocation> firstByFile = new HashMap<>();
    for (ToolInvocation invocation : sortedByCallIndex(invocations)) {
      Optional<ToolFileMutationOrder.Key> key = ToolFileMutationOrder.key(invocation);
      if (key.isPresent() && !invocation.status().isTerminal()) {
        firstByFile.putIfAbsent(key.get(), invocation);
      }
    }
    List<ToolInvocation> dispatchable = new ArrayList<>();
    for (ToolInvocation invocation : firstByFile.values()) {
      if (isDispatchableFileMutation(invocation) && !toolWorkExists(tx, invocation)) {
        dispatchable.add(invocation);
      }
    }
    requestAll(tx, dispatchable);
  }

  private static boolean toolWorkExists(HarnessStore.Transaction tx, ToolInvocation invocation) {
    return tx.work().find(new WorkTarget(WorkTargetType.TOOL, invocation.id())).isPresent();
  }

  private static void requestAll(HarnessStore.Transaction tx, List<ToolInvocation> dispatchable) {
    dispatchable.sort(Comparator.comparing(ToolInvocation::id, UuidOrder.COMPARATOR));
    for (ToolInvocation invocation : dispatchable) {
      requestToolWork(tx, invocation);
    }
  }

  private static List<ToolInvocation> sortedByCallIndex(List<ToolInvocation> invocations) {
    List<ToolInvocation> ordered = new ArrayList<>(invocations);
    ordered.sort(Comparator.comparingInt(ToolInvocation::callIndex));
    return ordered;
  }

  private static boolean isDispatchableFileMutation(ToolInvocation invocation) {
    return invocation.status() == ToolInvocationStatus.READY
        && invocation.attempt() == 0
        && invocation.approval() == null;
  }

  private static void requestToolWork(HarnessStore.Transaction tx, ToolInvocation invocation) {
    // Only required-environment capabilities route Work to an Environment. Per-call file tools
    // remain claimable even while that Environment is offline.
    EnvironmentId environmentId = requiredEnvironmentId(invocation);
    tx.work().request(new WorkTarget(WorkTargetType.TOOL, invocation.id()), environmentId);
  }

  /** 只有只能在环境上执行的工具才把 Tool Work 路由到环境；逐调用路由的文件工具不等待环境。 */
  private static EnvironmentId requiredEnvironmentId(ToolInvocation invocation) {
    ToolBinding binding = invocation.binding();
    if (binding == null || binding.environmentSupport() != EnvironmentSupport.REQUIRED) {
      return null;
    }
    return binding.environmentId();
  }
}
