package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;

import java.util.Objects;

/**
 * 同步、无副作用、事务外的 turn 解析端口：把 Thread 的当前 head {@link EntryPath} 解析为冻结的 {@link ModelInvocationRequest}。
 *
 * <p>调用方先用一次已提交的短事务捕获 Thread head 的 immutable {@link EntryPath} snapshot 与 frozen YOLO 开关，再在事务外调用
 * 本端口（Resolver 执行期间 Processor 只做 lease heartbeat，本端口绝不要求也不持有任何行锁）；实现只读取最新 catalog / environment
 * 事实，不得写 store、不得产生副作用、不得要求事务上下文。抛出的异常表示临时基础设施失败（DB / 网络不可用）， 由 Processor reschedule，绝不改写 durable
 * invocation。
 */
public interface TurnResolver {

  /** 解析一次 turn；临时基础设施失败以异常表达，由调用方 reschedule。 */
  Result resolve(long threadId, EntryPath path, boolean yoloEnabled);

  /** 解析结果：冻结请求或确定性拒绝。 */
  sealed interface Result permits TurnResolver.Resolved, TurnResolver.Rejected {}

  /** 解析成功：请求已完全冻结（route、tools、skills、YOLO）。 */
  record Resolved(ModelInvocationRequest request) implements Result {
    public Resolved {
      request = Objects.requireNonNull(request, "request");
    }
  }

  /** 确定性拒绝：写入 durable AssistantError barrier，不产生 ModelInvocation。 */
  record Rejected(AssistantError error) implements Result {
    public Rejected {
      error = Objects.requireNonNull(error, "error");
    }
  }
}
