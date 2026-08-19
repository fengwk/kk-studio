package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;

/**
 * 不可变的单次命令接受请求：封装一个 sealed {@link AcceptCommandsTarget}。
 *
 * <p>同一次创建（NEW_SESSION / ENTRY）的幂等键由 target 内的预分配 id 与 {@code materializationHash} 派生，不额外携带 request
 * id。
 */
public record AcceptCommandsCommand(AcceptCommandsTarget target) {

  public AcceptCommandsCommand {
    target = Objects.requireNonNull(target, "target");
  }
}
