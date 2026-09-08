package fun.fengwk.kkstudio.harness.provider.openai.chat;

import java.util.Objects;

/** 经编码序列化的 OpenAI Chat Completions HTTP 请求体与前缀哈希。 */
record OpenAiChatEncodedRequest(byte[] bodyUtf8Bytes, String sourcePrefixHash) {

  OpenAiChatEncodedRequest {
    Objects.requireNonNull(bodyUtf8Bytes, "bodyUtf8Bytes");
    Objects.requireNonNull(sourcePrefixHash, "sourcePrefixHash");
  }
}
