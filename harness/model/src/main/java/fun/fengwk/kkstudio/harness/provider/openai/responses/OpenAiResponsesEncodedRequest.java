package fun.fengwk.kkstudio.harness.provider.openai.responses;

import java.util.Objects;

/** 编码后的 OpenAI Responses 请求载荷。 */
public record OpenAiResponsesEncodedRequest(byte[] bodyUtf8Bytes) {

  public OpenAiResponsesEncodedRequest {
    Objects.requireNonNull(bodyUtf8Bytes, "bodyUtf8Bytes");
    bodyUtf8Bytes = bodyUtf8Bytes.clone();
  }

  @Override
  public byte[] bodyUtf8Bytes() {
    return bodyUtf8Bytes.clone();
  }
}
