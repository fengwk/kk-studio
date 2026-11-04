package fun.fengwk.kkstudio.platform.vfs;

import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.util.Objects;
import java.util.UUID;

/**
 * 以 Harness Runtime 的公开查询判断线程归属的校验器。
 *
 * <p>VFS 的 {@code threads/<线程 ID>/} 只接受当前对话中的线程：线程所属会话必须等于执行根会话。这里不直读 Harness 存储，也不复制 线程归属规则。
 *
 * <p>运行期通过 {@link ObjectProvider} 延迟取得 Runtime：Runtime 的构造依赖 Contributor（其中包含 VFS 工具），直接注入会形成循环。
 */
public final class HarnessThreadMembershipValidator implements ThreadMembershipValidator {

  private final ObjectProvider<HarnessRuntime> runtime;

  public HarnessThreadMembershipValidator(ObjectProvider<HarnessRuntime> runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public boolean isThreadInSession(UUID rootSessionId, UUID threadId) {
    if (rootSessionId == null || threadId == null) {
      return false;
    }
    HarnessRuntime resolved = runtime.getIfAvailable();
    if (resolved == null) {
      return false;
    }
    return resolved.findThreadSessionId(threadId).map(rootSessionId::equals).orElse(false);
  }
}
