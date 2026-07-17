package fun.fengwk.kkstudio.core.ai.image.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @author fengwk
 */
public enum GptImage2Size {

  /** 自动 */
  AUTO(""),

  /** 方形 1:1 */
  SQUARE("1:1"),

  /** 竖版 3:4 */
  PORTRAIT_3_4("3:4"),

  /** 故事版 9:16 */
  STORYBOARD_9_16("9:16"),

  /** 横版 4:3 */
  LANDSCAPE_4_3("4:3"),

  /** 宽屏 16:9 */
  WIDESCREEN_16_9("16:9");

  private final String value;

  GptImage2Size(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static GptImage2Size fromValue(String value) {
    if (value == null) {
      return null;
    }
    if ("auto".equals(value)) {
      return AUTO;
    }
    for (GptImage2Size size : values()) {
      if (size.value.equals(value)) {
        return size;
      }
    }
    throw new IllegalArgumentException("Unsupported GptImage2Size value: " + value);
  }

  @Override
  public String toString() {
    return value;
  }
}
