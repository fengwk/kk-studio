package fun.fengwk.kkstudio.harness.provider.anthropic;

import java.util.List;
import java.util.Objects;

/**
 * 编码完成的 Anthropic 请求体、其冻结的 sourcePrefixHash，以及本条请求实际发送的有序 beta 能力标识。
 *
 * <p>{@code betaFeatures} 是配置能力与运行时强制能力的并集（已去重）：为空表示不发送 {@code anthropic-beta} 头。
 */
record AnthropicEncodedRequest(
    byte[] bodyUtf8Bytes, String sourcePrefixHash, List<String> betaFeatures) {

  AnthropicEncodedRequest {
    Objects.requireNonNull(bodyUtf8Bytes, "bodyUtf8Bytes");
    Objects.requireNonNull(sourcePrefixHash, "sourcePrefixHash");
    betaFeatures = List.copyOf(Objects.requireNonNull(betaFeatures, "betaFeatures"));
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
        + ", betaFeatureCount="
        + betaFeatures.size()
        + "]";
  }
}
