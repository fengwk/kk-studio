package fun.fengwk.kkstudio.harness.provider.openai.responses;

import java.util.Objects;

/** 编码后的 OpenAI Responses 请求载荷与其冻结的 sourcePrefixHash。 */
record OpenAiResponsesEncodedRequest(byte[] bodyUtf8Bytes, String sourcePrefixHash) {

  OpenAiResponsesEncodedRequest {
    Objects.requireNonNull(bodyUtf8Bytes, "bodyUtf8Bytes");
    Objects.requireNonNull(sourcePrefixHash, "sourcePrefixHash");
    bodyUtf8Bytes = bodyUtf8Bytes.clone();
  }

  @Override
  public byte[] bodyUtf8Bytes() {
    return bodyUtf8Bytes.clone();
  }
}
