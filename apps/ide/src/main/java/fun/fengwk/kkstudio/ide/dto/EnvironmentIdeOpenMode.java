package fun.fengwk.kkstudio.ide.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 打开 IDE 视图的固定意图：新建视图或恢复既有视图；wire 值保持小写。 */
public enum EnvironmentIdeOpenMode {
  CREATE("create"),
  RESUME("resume");

  private final String wireValue;

  EnvironmentIdeOpenMode(String wireValue) {
    this.wireValue = wireValue;
  }

  /** 固定 wire 值。 */
  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  /** 按 wire 值解析；未知意图抛 {@link IllegalArgumentException}。 */
  @JsonCreator
  public static EnvironmentIdeOpenMode fromWireValue(String value) {
    return Arrays.stream(values())
        .filter(mode -> mode.wireValue.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown IDE open mode: " + value));
  }
}
