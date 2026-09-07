package fun.fengwk.kkstudio.harness.runtime.model.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.regex.Pattern;

/** Provider native terminal replay 状态，持有上游 native 消息结构或上下文句柄。 */
public record ProviderReplayState(
    ProviderReplayFormat format,
    ProviderReplayAffinity affinity,
    String sourcePrefixHash,
    JsonNode payload) {

  private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

  public ProviderReplayState {
    format = Objects.requireNonNull(format, "format");
    affinity = Objects.requireNonNull(affinity, "affinity");
    if (sourcePrefixHash == null || !HASH_PATTERN.matcher(sourcePrefixHash).matches()) {
      throw new IllegalArgumentException(
          "sourcePrefixHash must be a 64-character lowercase hex string");
    }
    Objects.requireNonNull(payload, "payload");
    if (!payload.isObject()) {
      throw new IllegalArgumentException("payload must be a JSON object");
    }
    payload = payload.deepCopy();
  }

  @Override
  public JsonNode payload() {
    return payload.deepCopy();
  }

  @Override
  public String toString() {
    return "ProviderReplayState[format="
        + format
        + ", affinity="
        + affinity
        + ", sourcePrefixHash="
        + sourcePrefixHash
        + ", payloadSize="
        + payload.size()
        + "]";
  }
}
