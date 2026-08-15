package fun.fengwk.kkstudio.harness.tool.daemon;

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
  LOAD_SKILL,
  SKILL_LOADED,
  SKILL_LOAD_FAILED,
  LIST_DIRECTORY,
  DIRECTORY_LISTED,
  DIRECTORY_LIST_FAILED,
  ACK,
  ERROR
}
