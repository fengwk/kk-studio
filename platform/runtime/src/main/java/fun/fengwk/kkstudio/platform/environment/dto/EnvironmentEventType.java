package fun.fengwk.kkstudio.platform.environment.dto;

/** Environment 运维事件的封闭类型集合；取值与 registry 追加事件时的 wire 值一致。 */
public enum EnvironmentEventType {
  CONNECTING,
  READY,
  DISCONNECTED,
  SKILL_SYNC_STARTED,
  SKILL_SYNC_SUCCEEDED,
  SKILL_SYNC_FAILED,
  SKILL_STATE_UPDATED,
  UPDATE_FAILED
}
