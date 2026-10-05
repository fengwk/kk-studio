package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.Objects;

/**
 * 不可变 Stop 结果。
 *
 * <p>{@code replayed=true} 表示重放了一次先前 Stop 的持久回执，本次调用不写任何 marker、不触碰 version；{@code stoppedThreads}
 * 直接来自持久保存的旧范围，不以当前树重算。首次 Stop 时 {@code stoppedThreads} 包含本次完整受影响集合（请求目标 加上全部后代）。{@code thread}
 * 始终是请求目标的当前权威投影。
 */
public record StopResult(
    boolean replayed, ThreadState thread, List<StoppedThreadReceipt> stoppedThreads) {

  public StopResult {
    thread = Objects.requireNonNull(thread, "thread");
    stoppedThreads = List.copyOf(Objects.requireNonNull(stoppedThreads, "stoppedThreads"));
  }
}
