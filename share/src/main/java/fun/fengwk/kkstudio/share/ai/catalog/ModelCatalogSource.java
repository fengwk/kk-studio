package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 系统设置中选择的模型目录来源；两者使用独立适配器，绝不自动回退或混合。 */
public enum ModelCatalogSource {
  MODELS_DEV_V1("models_dev_v1"),
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
