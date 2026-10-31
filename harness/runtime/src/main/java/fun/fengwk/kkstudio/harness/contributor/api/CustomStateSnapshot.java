package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.common.json.JsonValues;

import java.util.Objects;

/**
 * 自定义状态快照。
 *
 * @param dataJson 单一 JSON 值文本（严格校验，保留原文），不得为 null
 */
public record CustomStateSnapshot(String dataJson) {

  public CustomStateSnapshot {
    dataJson = JsonValues.requireValidJson(Objects.requireNonNull(dataJson, "dataJson"));
  }
}
