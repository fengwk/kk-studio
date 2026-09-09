package fun.fengwk.kkstudio.harness.provider.anthropic;

import java.util.Objects;

/** 编码完成的 Anthropic 请求体及其冻结的 sourcePrefixHash 与 beta 请求头标记。 */
record AnthropicEncodedRequest(
    byte[] bodyUtf8Bytes, String sourcePrefixHash, boolean requiresInterleavedThinkingBeta) {

  AnthropicEncodedRequest {
    Objects.requireNonNull(bodyUtf8Bytes, "bodyUtf8Bytes");
    Objects.requireNonNull(sourcePrefixHash, "sourcePrefixHash");
    bodyUtf8Bytes = bodyUtf8Bytes.clone();
  }

  @Override
  public byte[] bodyUtf8Bytes() {
    return bodyUtf8Bytes.clone();
  }

  @Override
  public String toString() {
    return "AnthropicEncodedRequest[bodyBytes="
        + bodyUtf8Bytes.length
        + ", requiresInterleavedThinkingBeta="
        + requiresInterleavedThinkingBeta
        + "]";
  }
}
