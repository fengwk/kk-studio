package fun.fengwk.kkstudio.ide.protocol;

/** IDE 视图的打开模式，wire 值为小写 {@code create}/{@code resume}。 */
public enum IdeOpenMode {
  CREATE("create"),
  RESUME("resume");

  private final String wireValue;

  IdeOpenMode(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static IdeOpenMode fromWire(String value) {
    for (IdeOpenMode mode : values()) {
      if (mode.wireValue.equals(value)) {
        return mode;
      }
    }
    throw new IllegalArgumentException("unknown IDE open mode");
  }
}
