package fun.fengwk.kkstudio.harness.environment.terminal;

/**
 * 终端生命周期状态。
 *
 * <p>{@link #RUNNING} 表示 shell 仍在运行，必须没有退出码；{@link #EXITED} 表示已自然退出，必须携带退出码；{@link #FAILED}
 * 表示以失败/未知方式结束，退出码可有可无。{@code EXITED} 事件本身不得声明 {@link #RUNNING}。
 */
public enum TerminalStatus {
  RUNNING,
  EXITED,
  FAILED
}
