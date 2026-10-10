package fun.fengwk.kkstudio.harness.environment.daemon;

/** 反向数据面的转发模式：HTTP exchange 或 IDE 原生 WebSocket。 */
public enum IdeStreamMode {
  HTTP,
  WS
}
