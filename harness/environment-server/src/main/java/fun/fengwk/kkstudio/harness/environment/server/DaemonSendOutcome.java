package fun.fengwk.kkstudio.harness.environment.server;

/** 一次出站发送的确定性结果。 */
public enum DaemonSendOutcome {

  /** 帧已成功交给传输。 */
  SENT,

  /** 帧确定尚未发送（连接已清理、发送失败或传输明确拒绝）。 */
  NOT_SENT,

  /** 帧已尝试写入传输但送达不可确认。 */
  UNCERTAIN
}
