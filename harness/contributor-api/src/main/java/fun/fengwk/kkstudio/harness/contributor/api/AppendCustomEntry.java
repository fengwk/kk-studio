package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;

/**
 * 追加一条 CUSTOM Entry 的声明式意图。
 *
 * @param customType 自定义状态类型，必须为 canonical 小写 dotted/dashed 标识符
 * @param schemaVersion 正数 schema 版本
 * @param dataJson 状态 JSON 字符串，不得为 null
 */
public record AppendCustomEntry(String customType, int schemaVersion, String dataJson) {

  public AppendCustomEntry {
    customType = Identifiers.requireCanonical(customType, "customType");
    if (schemaVersion <= 0) {
      throw new IllegalArgumentException("schemaVersion must be positive: " + schemaVersion);
    }
    dataJson = Objects.requireNonNull(dataJson, "dataJson");
  }
}
