package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;
import java.util.UUID;

/**
 * ToolResult MESSAGE 的稳定 metadata。
 *
 * <p>{@code assistantEntryId} 是产生 ToolCall 的 ASSISTANT Entry；{@code toolCallId} 与 Assistant
 * ToolCall 关联；{@code ordinal} 是该 Assistant response 内的 ToolCall 序号。synthetic 结果（history
 * normalization 补写）必须是 {@code status=UNKNOWN} + {@code reason=HISTORY_CUT}，且不关联任何 ToolInvocation； 非
 * synthetic 结果不允许携带 reason。
 */
public record ToolResultMetadata(
    UUID assistantEntryId,
    String toolCallId,
    int ordinal,
    ToolResultStatus status,
    boolean synthetic,
    ToolResultReason reason) {

  private static final int TOOL_CALL_ID_MAX_LENGTH = 256;

  public ToolResultMetadata {
    Objects.requireNonNull(assistantEntryId, "assistantEntryId");
    toolCallId = requireCanonicalName(toolCallId, "toolCallId");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must be >= 0");
    }
    status = Objects.requireNonNull(status, "status");
    if (synthetic) {
      if (status != ToolResultStatus.UNKNOWN || reason != ToolResultReason.HISTORY_CUT) {
        throw new IllegalArgumentException(
            "synthetic tool results must be UNKNOWN with HISTORY_CUT reason");
      }
    } else if (reason != null) {
      throw new IllegalArgumentException("non-synthetic tool results must not carry a reason");
    }
  }

  private static String requireCanonicalName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > TOOL_CALL_ID_MAX_LENGTH) {
      throw new IllegalArgumentException(
          field + " must be <= " + TOOL_CALL_ID_MAX_LENGTH + " characters");
    }
    return value;
  }
}
