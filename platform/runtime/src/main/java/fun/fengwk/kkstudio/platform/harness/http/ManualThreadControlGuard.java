package fun.fengwk.kkstudio.platform.harness.http;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.util.UUID;

/**
 * 人工 Thread 控制边界：只允许对执行根执行 Thread 级控制。
 *
 * <p>用 {@link HarnessRuntime#findRootThreadId} 判定目标是否为根：执行根不是自身的子 Thread 以 409 拒绝，避免把根级输入或控制落到
 * 观察树内部。不存在的 Thread 不在此判定，交由后续 Runtime 调用按既有语义翻译为 404。
 *
 * <p>内部 task/resume 与 Runtime 原语不经本边界，因此不受限制；审批与问卷回答写回子调用本身，是合法例外，也不经过本判定。
 */
final class ManualThreadControlGuard {

  private ManualThreadControlGuard() {}

  /** 目标必须是执行根，否则以 409 拒绝人工控制。 */
  static void requireExecutionRoot(HarnessRuntime runtime, UUID threadId) {
    if (runtime.findRootThreadId(threadId).filter(root -> !root.equals(threadId)).isPresent()) {
      throw DomainException.conflict(
          "MANUAL_THREAD_CONTROL_ROOT_REQUIRED",
          "manual thread control is limited to the execution root");
    }
  }

}
