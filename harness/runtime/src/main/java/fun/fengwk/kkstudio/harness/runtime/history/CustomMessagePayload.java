package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;

/**
 * 业务扩展注入的对话消息 Entry：冻结的 {@link AgentMessage}（USER）保持 model-visible，{@code detailsJson} 是 bounded
 * canonical JSON object 字符串且绝不投影给 provider。
 *
 * <p>非 contributor（platform command）产生的 CUSTOM_MESSAGE 使用稳定的内置消息元数据常量：contributorId {@code
 * core}、customType {@code message}、rendererKey {@code message}、details {@code {}}。
 */
public record CustomMessagePayload(
    String contributorId,
    String customType,
    String rendererKey,
    AgentMessage message,
    String detailsJson)
    implements EntryPayload {

  /** 非 contributor custom command 消息的稳定 contributorId。 */
  public static final String CORE_CONTRIBUTOR_ID = "core";

  /** 非 contributor custom command 消息的稳定 customType。 */
  public static final String CORE_CUSTOM_TYPE = "message";

  /** 非 contributor custom command 消息的稳定 rendererKey。 */
  public static final String CORE_RENDERER_KEY = "message";

  /** 非 contributor custom command 消息的稳定 details。 */
  public static final String CORE_DETAILS_JSON = "{}";

  /** {@code detailsJson} 的最大字符数（bounded）。 */
  public static final int MAX_DETAILS_JSON_CHARS = 16 * 1024;

  public CustomMessagePayload {
    HistoryValueCodecs.requireCanonicalIdentifier(contributorId, "contributorId");
    HistoryValueCodecs.requireCanonicalIdentifier(customType, "customType");
    HistoryValueCodecs.requireCanonicalIdentifier(rendererKey, "rendererKey");
    message = Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("custom message role must be USER");
    }
    Objects.requireNonNull(detailsJson, "detailsJson");
    JsonObjects.requireCanonicalObjectJson(detailsJson, "detailsJson", MAX_DETAILS_JSON_CHARS);
  }

  @Override
  public EntryType type() {
    return EntryType.CUSTOM_MESSAGE;
  }
}
