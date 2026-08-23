package fun.fengwk.kkstudio.harness.plugin.api;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;

import java.util.Objects;

/**
 * 追加一条 CUSTOM Entry 的声明式意图：持有最终 Entry payload（{@link CustomEntryPayload}），因此自动保留
 * pluginId/customType/schemaVersion/dataJson 元数据并继承 payload 的严格校验。执行时 Harness 校验 ownership。
 */
public record AppendCustomEntry(CustomEntryPayload payload) {

  public AppendCustomEntry {
    payload = Objects.requireNonNull(payload, "payload");
  }
}
