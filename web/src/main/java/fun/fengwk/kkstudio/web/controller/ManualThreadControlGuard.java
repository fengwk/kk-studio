package fun.fengwk.kkstudio.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.UUID;

/**
 * 人工 Thread 写入边界：自由输入、Goal、设置、YOLO、Stop、压缩、重命名与分支创建等人工控制只允许指向执行根。
 *
 * <p>用 {@link HarnessRuntime#findAncestorChain}（head-to-root，含自身）判定目标是否为根：子 Thread（祖先链长于 1）以 409
 * 拒绝，避免把根级输入或控制落到观察树内部。不存在的 Thread（空链）不在此判定，交由后续 Runtime 调用按既有语义翻译为 404。
 *
 * <p>NEW_THREAD 人工 fork 没有目标 Thread，只按会话定位：来源会话内一旦出现带父关系的 Thread（子执行树会话）即 409 拒绝。不能用
 * 「会话无产品归属」来推断来源属于根——真正成立的判据是会话内不存在 child Thread；这样即使同一会话里混入子 Thread 也不会漏判。
 *
 * <p>内部 task/resume 与 Runtime 原语不经本边界，因此不受限制；审批与问卷回答写回子调用本身，是合法例外，也不经过本判定。
 */
final class ManualThreadControlGuard {

  private ManualThreadControlGuard() {}

  /** 目标必须是执行根，否则以 409 拒绝人工控制。 */
  static void requireExecutionRoot(HarnessRuntime runtime, UUID threadId) {
    List<UUID> chain = runtime.findAncestorChain(threadId);
    if (chain.size() > 1) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "manual thread control is limited to the execution root");
    }
  }

  /** NEW_THREAD 人工 fork 的来源会话必须是纯执行根会话：会话内任何带父关系的 Thread 都说明该会话承载子执行树，409 拒绝，不能凭会话无产品归属 反推来源属于根。 */
  static void requireForkRootSession(HarnessRuntime runtime, UUID sessionId) {
    for (ThreadState thread : runtime.listThreadsBySession(sessionId)) {
      if (thread.parentThreadId() != null) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "manual thread fork is limited to execution roots");
      }
    }
  }
}
