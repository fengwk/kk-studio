package fun.fengwk.kkstudio.harness.runtime.session;

/** 扩展自定义状态；默认 Context transform 永不将它送入模型。 */
public record CustomEntryPayload(String name, String dataJson) implements SessionEntryPayload {
  public CustomEntryPayload {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (dataJson == null || dataJson.isBlank()) {
      throw new IllegalArgumentException("dataJson must not be blank");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.CUSTOM;
  }
}
