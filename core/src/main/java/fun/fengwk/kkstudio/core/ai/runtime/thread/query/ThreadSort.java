package fun.fengwk.kkstudio.core.ai.runtime.thread.query;

/** Stable Thread list sort modes backed by the authoritative Thread timestamps. */
public enum ThreadSort {
  RECENT("recent"),
  CREATED("created");

  private final String wireValue;

  ThreadSort(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static ThreadSort parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return RECENT;
    }
    for (ThreadSort value : values()) {
      if (value.wireValue.equals(raw)) {
        return value;
      }
    }
    throw new IllegalArgumentException("sort must be recent or created");
  }
}
