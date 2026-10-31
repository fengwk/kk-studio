package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 模型目录来源。 */
public enum ModelCatalogSource {
  MODELS_DEV_V2("models_dev_v2");

  private final String value;

  ModelCatalogSource(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ModelCatalogSource fromValue(String value) {
    if (value != null) {
      for (ModelCatalogSource source : values()) {
        if (source.value.equals(value)) {
          return source;
        }
      }
    }
    throw new IllegalArgumentException("unknown model catalog source: " + value);
  }
}
