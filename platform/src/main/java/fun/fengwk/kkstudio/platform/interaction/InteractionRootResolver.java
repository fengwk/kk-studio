package fun.fengwk.kkstudio.platform.interaction;

import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 统一交互的窄共用根解析：沿来源 Thread 的不可变祖先链定位真实执行根。
 *
 * <p>查询、提交与审批共用同一解析，保证同根归属一致：真实根只取 head-to-root 链的末位，绝不按中间父节点或来源自身推断。 Thread 不存在（链为空）时显式抛 {@link
 * HarnessRuntimeNotFoundException}，不静默回退为自身；Runtime 未就绪抛 {@link IllegalStateException}。本类只做根定位与
 * Runtime 访问，不承担 owner 组装或锁序，不构成通用框架。
 */
final class InteractionRootResolver {

  private final ObjectProvider<HarnessRuntime> runtimes;

  InteractionRootResolver(ObjectProvider<HarnessRuntime> runtimes) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  /** 返回来源 Thread 的真实执行根（head-to-root 末位）；不存在抛 not-found。 */
  UUID requireRootId(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    List<UUID> chain = requireRuntime().findAncestorChain(threadId);
    if (chain.isEmpty()) {
      throw new HarnessRuntimeNotFoundException("thread " + threadId + " does not exist");
    }
    return chain.get(chain.size() - 1);
  }

  /** 同一 Runtime 访问口，供 owner 快照/绑定的只读解析复用；未就绪确定性拒绝。 */
  HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}
