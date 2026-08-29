package fun.fengwk.kkstudio.harness.environment.daemon;

/** Daemon wire 协议的消息类型。 */
public enum DaemonMessageType {
  HELLO,
  READY,
  HEARTBEAT,
  STARTED,
  PARTIAL,
  COMPLETED,
  FAILED,
  CANCELLED,
  WELCOME,
  INVOKE,
  CANCEL,
  ACK,
  ERROR
}
