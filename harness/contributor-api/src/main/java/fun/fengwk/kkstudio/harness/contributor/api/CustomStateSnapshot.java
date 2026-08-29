package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;

/**
 * 自定义状态快照。
 *
 * @param schemaVersion 正数 schema 版本
 * @param dataJson 状态 JSON 字符串，不得为 null
 */
public record CustomStateSnapshot(int schemaVersion, String dataJson) {

  public CustomStateSnapshot {
    if (schemaVersion <= 0) {
      throw new IllegalArgumentException("schemaVersion must be positive: " + schemaVersion);
    }
    Objects.requireNonNull(dataJson, "dataJson");
  }
}
