package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.util.Objects;

/**
 * 自定义状态快照。
 *
 * @param schemaVersion 正数 schema 版本
 * @param dataJson 单一 JSON 值文本（严格校验，保留原文），不得为 null
 */
public record CustomStateSnapshot(int schemaVersion, String dataJson) {

  public CustomStateSnapshot {
    if (schemaVersion <= 0) {
      throw new IllegalArgumentException("schemaVersion must be positive: " + schemaVersion);
    }
    dataJson = JsonValues.requireValidJson(Objects.requireNonNull(dataJson, "dataJson"));
  }
}
