package fun.fengwk.kkstudio.harness.model.provider;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 一个 assistant block 的 Provider 身份与该 block 独有的原生字段。
 *
 * <p>Provider identity、协议语义字段（signature、redacted/encrypted data、item id）及可读内容均强类型表达。{@code
 * nativeFields} 只保存 kk 不解释、但同协议原样回放所需的开放 Provider 扩展字段和未知 union/item；将所有扩展镜像为 DTO 会引入四套易变
 * schema，且无法保留未知字段。
 */
public record ProviderBlockData(
    ProviderReplayFormat protocol,
    ProviderReplayAffinity affinity,
    String signature,
    String encryptedContent,
    String itemId,
    String itemType,
    String contentType,
    boolean refusal,
    JsonNode nativeFields) {

  public ProviderBlockData(
      ProviderReplayFormat protocol, ProviderReplayAffinity affinity, JsonNode nativeFields) {
    this(protocol, affinity, null, null, null, null, null, false, nativeFields);
  }

  public ProviderBlockData(
      ProviderReplayFormat protocol,
      ProviderReplayAffinity affinity,
      String signature,
      String encryptedContent,
      String itemId,
      JsonNode nativeFields) {
    this(protocol, affinity, signature, encryptedContent, itemId, null, null, false, nativeFields);
  }

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
}
