package fun.fengwk.kkstudio.harness.runtime.cache;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 派生稳定的 Prompt Cache affinity key。
 *
 * <p>finalizer 唯一可信派生点；同一 session + provider connection generation/model + 稳定 system
 * instruction/tool 前缀的请求始终命中同一 key，动态 USER/ASSISTANT/TOOL history 绝不进入 key。
 *
 * <p>实现要点：
 *
 * <ul>
 *   <li>格式固定为 {@code pc2-} 前缀 + SHA-256 Base64URL 无 padding（43 字符）。
 *   <li>digest 输入采用 {@code (type, nameLen:4B, name, valueLen:4B, value)} 的长度前缀帧； 每个复合对象的每个
 *       字段必须单独成帧，绝不依赖分隔字符或 NUL 拼接，因此字段值包含 NUL 也不会与跨字段拼接碰撞。
 *   <li>版本字段独立标记，用于将来在不破坏旧 key 的前提下增量调整算法。
 *   <li>输入：版本、sessionId、providerName、providerConnectionGenerationId、modelId（真实 wire 模型标识）、唯一的
 *       system instruction 全文，以及按请求顺序的 tool name/description/inputSchemaJson。Provider 类型与
 *       capability 由调用方显式解析、不随请求持久化，因此也不进入 key。
 * </ul>
 */
public final class PromptCacheAffinityKeyFactory {

  private static final String VERSION = "pc2";

  /**
   * 为单次请求派生 affinity key。
   *
   * @param sessionId durable session UUID
   * @param providerConnectionGenerationId provider 连接代际 UUID
   * @param request 不可为 null，且其 model 不能为空
   * @return 形如 {@code pc2-<Base64URL-无padding>} 的字符串
   */
  public String create(
      UUID sessionId, UUID providerConnectionGenerationId, ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(providerConnectionGenerationId, "providerConnectionGenerationId");
    ModelDescriptor model = Objects.requireNonNull(request.model(), "request.model()");
    byte[] digest =
        digest(
            sessionId,
            providerConnectionGenerationId,
            model,
            request.systemInstruction(),
            request.tools());
    return VERSION + "-" + encode(digest);
  }

  private static byte[] digest(
      UUID sessionId,
      UUID providerConnectionGenerationId,
      ModelDescriptor model,
      String systemInstruction,
      List<ProviderToolDefinition> tools) {
    MessageDigest md = newMessageDigest();
    writeField(md, 'V', "version", VERSION);
    writeField(md, 'S', "sessionId", sessionId.toString());
    writeField(md, 'P', "providerName", model.providerName());
    writeField(
        md, 'G', "providerConnectionGenerationId", providerConnectionGenerationId.toString());
    writeField(md, 'M', "modelId", model.modelId());
    writeField(md, 'X', "systemInstruction", systemInstruction);
    writeTools(md, tools);
    return md.digest();
  }

  private static MessageDigest newMessageDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void writeTools(MessageDigest md, List<ProviderToolDefinition> tools) {
    writeField(md, 'L', "toolCount", Integer.toString(tools.size()));
    for (int index = 0; index < tools.size(); index++) {
      writeField(md, 'O', "tool#" + index + "/name", tools.get(index).name());
      writeField(md, 'D', "tool#" + index + "/description", tools.get(index).description());
      writeField(md, 'J', "tool#" + index + "/inputSchemaJson", tools.get(index).inputSchemaJson());
    }
  }

  /**
   * 写入一条独立长度帧：{@code type (1B) | nameLen (4B big-endian) | name | valueLen (4B big-endian) |
   * value}。 每个复合对象的每个字段都独立走此帧，因此字段值可以包含任意字节（含 NUL）而不会与跨字段拼接产生碰撞。
   */
  private static void writeField(MessageDigest md, char type, String name, String value) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(value, "value");
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
    md.update((byte) type);
    md.update(intBE(nameBytes.length));
    md.update(nameBytes);
    md.update(intBE(valueBytes.length));
    md.update(valueBytes);
  }

  private static byte[] intBE(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("length must be non-negative");
    }
    return new byte[] {
      (byte) ((value >>> 24) & 0xFF),
      (byte) ((value >>> 16) & 0xFF),
      (byte) ((value >>> 8) & 0xFF),
      (byte) (value & 0xFF)
    };
  }
}
