package fun.fengwk.kkstudio.harness.model.provider;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;

import java.util.Objects;

/**
 * 一个 assistant block 的 Provider 身份与该 block 独有的原生字段。
 *
 * <p>语义内容使用强类型 block 表达；{@code nativeFields} 保持 JSON object，是因为协议允许开放字段和未知 union/item，且回放必须原样保留这些
 * Provider 扩展。将它们建模为强类型 DTO 会要求镜像四套可扩展 Provider schema，并在未知字段出现时丢失事实。协议与 Provider 身份仍使用强类型字段。
 */
public record ProviderBlockData(
    ProviderReplayFormat protocol, ProviderReplayAffinity affinity, JsonNode nativeFields) {

  private static final ObjectMapper MAPPER = HarnessJson.STRICT;

  public ProviderBlockData {
    protocol = Objects.requireNonNull(protocol, "protocol");
    affinity = Objects.requireNonNull(affinity, "affinity");
    nativeFields = Objects.requireNonNull(nativeFields, "nativeFields").deepCopy();
    if (!nativeFields.isObject()) {
      throw new IllegalArgumentException("nativeFields must be a JSON object");
    }
  }

  @Override
  public JsonNode nativeFields() {
    return nativeFields.deepCopy();
  }

  @Override
  public String toString() {
    return "ProviderBlockData[protocol="
        + protocol
        + ", providerType="
        + affinity.providerType()
        + "]";
  }

  /** 构造并校验一组 block-native JSON 字段。 */
  public static ProviderBlockData fromJson(
      ProviderReplayFormat protocol, ProviderReplayAffinity affinity, String nativeFieldsJson) {
    Objects.requireNonNull(nativeFieldsJson, "nativeFieldsJson");
    JsonNode fields;
    try {
      fields = MAPPER.readTree(nativeFieldsJson);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("nativeFieldsJson must be a JSON object", error);
    }
    if (!(fields instanceof ObjectNode)) {
      throw new IllegalArgumentException("nativeFieldsJson must be a JSON object");
    }
    return new ProviderBlockData(protocol, affinity, fields);
  }
}
