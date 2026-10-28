package fun.fengwk.kkstudio.ide.protocol;

/** 视图的运行状态；{@code CLOSING} 仍占用额度且不可恢复。 */
public enum IdeViewState {
  OPEN,
  CLOSING;

  public static IdeViewState fromWire(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new IllegalArgumentException("unknown IDE view state");
    }
  }
}
