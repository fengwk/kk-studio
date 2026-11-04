package fun.fengwk.kkstudio.harness.spi.driver;

import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Driver 注册的一种 Work 处理器。
 *
 * <p>组合根只负责把注册结果交给 Dispatcher；类型的环境亲和性由 {@link WorkTargetType} 自己声明。处理器在 Dispatcher 的 worker
 * 线程上、事务外执行，必须自行取得 ownership fence 并提交/重排 durable 状态。
 *
 * @param type Driver 自己的 Work 类型；不得占用内核的 THREAD / TOOL
 * @param handler claim 的处理器
 */
public record DriverWork(WorkTargetType type, Consumer<ClaimedWork> handler) {

  public DriverWork {
    type = Objects.requireNonNull(type, "type");
    handler = Objects.requireNonNull(handler, "handler");
  }

  public void process(ClaimedWork claim) {
    handler.accept(claim);
  }
}
