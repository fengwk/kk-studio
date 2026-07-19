package fun.fengwk.kkstudio.harness.runtime.session;

/** Branch 路径上的 YOLO 配置变更；独立 fold，不随 Agent 切换隐式改变。 */
public record YoloChangeEntryPayload(boolean yoloEnabled) implements SessionEntryPayload {
  @Override
  public SessionEntryType type() {
    return SessionEntryType.YOLO_CHANGE;
  }
}
