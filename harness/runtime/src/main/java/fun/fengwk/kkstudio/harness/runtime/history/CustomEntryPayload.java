package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;

/**
 * 业务 Contributor 追加的透明 branch state Entry：不参与 turn grammar（允许 ROOT 后任意位置，包括 open/closed turn），
 * provider 消息投影默认忽略它。字段：canonical 小写 dotted/dashed {@code contributorId} / {@code customType}、 正数
 * {@code schemaVersion} 与 bounded canonical JSON object 字符串 {@code dataJson}。
 */
public record CustomEntryPayload(
    String contributorId, String customType, int schemaVersion, String dataJson)
    implements EntryPayload {

  /** {@code dataJson} 的最大字符数（bounded）。 */
  public static final int MAX_DATA_JSON_CHARS = 64 * 1024;

  public CustomEntryPayload {
    HistoryValueCodecs.requireCanonicalIdentifier(contributorId, "contributorId");
    HistoryValueCodecs.requireCanonicalIdentifier(customType, "customType");
    if (schemaVersion <= 0) {
      throw new IllegalArgumentException("schemaVersion must be positive");
    }
    Objects.requireNonNull(dataJson, "dataJson");
    JsonObjects.requireCanonicalObjectJson(dataJson, "dataJson", MAX_DATA_JSON_CHARS);
  }

  @Override
  public EntryType type() {
    return EntryType.CUSTOM;
  }
}
