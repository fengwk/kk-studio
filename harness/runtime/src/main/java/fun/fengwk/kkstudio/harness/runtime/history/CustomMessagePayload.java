package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;

/**
 * 业务扩展注入的对话消息 Entry：冻结的 {@link AgentMessage}（SYSTEM / USER）保持 model-visible，{@code detailsJson} 是
 * bounded canonical JSON object 字符串且绝不投影给 provider。
 *
 * <p>非插件（platform command）产生的 CUSTOM_MESSAGE 使用稳定的内置消息元数据常量：pluginId {@code core}、customType {@code
 * message}、rendererKey {@code message}、details {@code {}}。
 */
public record CustomMessagePayload(
    String pluginId,
    String customType,
    String rendererKey,
    AgentMessage message,
    String detailsJson)
    implements EntryPayload {

  /** 非插件 custom command 消息的稳定 pluginId。 */
  public static final String CORE_PLUGIN_ID = "core";

  /** 非插件 custom command 消息的稳定 customType。 */
  public static final String CORE_CUSTOM_TYPE = "message";

  /** 非插件 custom command 消息的稳定 rendererKey。 */
  public static final String CORE_RENDERER_KEY = "message";

  /** 非插件 custom command 消息的稳定 details。 */
  public static final String CORE_DETAILS_JSON = "{}";

  /** {@code detailsJson} 的最大字符数（bounded）。 */
  public static final int MAX_DETAILS_JSON_CHARS = 16 * 1024;

  public CustomMessagePayload {
    HistoryValueCodecs.requireCanonicalIdentifier(pluginId, "pluginId");
    HistoryValueCodecs.requireCanonicalIdentifier(customType, "customType");
    HistoryValueCodecs.requireCanonicalIdentifier(rendererKey, "rendererKey");
    message = Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.SYSTEM && message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("custom message role must be SYSTEM or USER");
    }
    Objects.requireNonNull(detailsJson, "detailsJson");
    JsonObjects.requireCanonicalObjectJson(detailsJson, "detailsJson", MAX_DETAILS_JSON_CHARS);
  }

  @Override
  public EntryType type() {
    return EntryType.CUSTOM_MESSAGE;
  }
}
