package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.Objects;
import java.util.UUID;

/**
 * 一个终端在 Daemon 实例内的稳定身份。
 *
 * <p>{@code daemonInstanceId} 标识承载终端的 Daemon 进程实例，{@code terminalId} 标识该实例内的一个终端。两者共同构成控制命令、事件与恢复的
 * 作用域；两个字段都不可空，且都必须是 canonical 小写 UUID 才能进入 wire。
 *
 * @param daemonInstanceId 承载终端的 Daemon 实例 id
 * @param terminalId 实例内终端 id
 */
public record TerminalIdentity(UUID daemonInstanceId, UUID terminalId) {

  public TerminalIdentity {
    Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
    Objects.requireNonNull(terminalId, "terminalId");
  }
}
