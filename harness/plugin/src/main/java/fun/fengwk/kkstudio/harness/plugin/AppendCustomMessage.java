package fun.fengwk.kkstudio.harness.plugin;

import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;

import java.util.Objects;

/**
 * 追加一条 CUSTOM_MESSAGE Entry 的声明式意图：持有最终 Entry payload（{@link CustomMessagePayload}），因此自动保留
 * pluginId/customType/rendererKey/detailsJson 元数据并继承 payload 的严格校验（SYSTEM/USER role、canonical
 * details）。
 */
public record AppendCustomMessage(CustomMessagePayload payload) implements PluginIntent {

  public AppendCustomMessage {
    payload = Objects.requireNonNull(payload, "payload");
  }
}
