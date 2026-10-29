package fun.fengwk.kkstudio.harness.provider.gemini;

import java.util.Objects;

/** 编码完成的 Gemini 请求体。 */
record GeminiEncodedRequest(byte[] bodyUtf8Bytes) {

  GeminiEncodedRequest {
    Objects.requireNonNull(bodyUtf8Bytes, "bodyUtf8Bytes");
    bodyUtf8Bytes = bodyUtf8Bytes.clone();
  }

  @Override
  public byte[] bodyUtf8Bytes() {
    return bodyUtf8Bytes.clone();
  }

  @Override
  public String toString() {
    return "GeminiEncodedRequest[bodyBytes=" + bodyUtf8Bytes.length + "]";
  }
}
