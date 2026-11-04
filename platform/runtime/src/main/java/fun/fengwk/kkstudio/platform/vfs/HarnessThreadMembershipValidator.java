package fun.fengwk.kkstudio.platform.vfs;

import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.util.Objects;
import java.util.UUID;

/**
 * 以 Harness Runtime 的公开查询判断线程归属的校验器。
 *
 * <p>VFS 的 {@code threads/<线程 ID>/} 只接受当前对话中的线程：该线程的执行根必须等于当前调用的执行根。子 Agent 线程运行在独立 Session
 * 中，但执行根不变，因此仍然可用。这里不直读 Harness 存储，也不复制线程归属规则。
 *
 * <p>运行期通过 {@link ObjectProvider} 延迟取得 Runtime：Runtime 的构造依赖 Contributor（其中包含 VFS 工具），直接注入会形成循环。
 */
public final class HarnessThreadMembershipValidator implements ThreadMembershipValidator {

  private final ObjectProvider<HarnessRuntime> runtime;

  public HarnessThreadMembershipValidator(ObjectProvider<HarnessRuntime> runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public boolean isThreadInExecutionRoot(UUID rootThreadId, UUID threadId) {
    if (rootThreadId == null || threadId == null) {
      return false;
    }
    HarnessRuntime resolved = runtime.getIfAvailable();
    if (resolved == null) {
      return false;
    }
    return resolved.findRootThreadId(threadId).map(rootThreadId::equals).orElse(false);
  }
}
