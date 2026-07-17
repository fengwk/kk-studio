package fun.fengwk.kkstudio.harness.runtime.cache;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 派生稳定的 Prompt Cache affinity key。
 *
 * <p>finalizer 唯一可信派生点；同一 session + provider/model + 稳定 system/tool 前缀的请求始终命中同一 key， 动态
 * USER/ASSISTANT/TOOL history 与 sampling 参数绝不进入 key。
 *
 * <p>实现要点：
 *
 * <ul>
 *   <li>格式固定为 {@code pc1-} 前缀 + SHA-256 Base64URL 无 padding（43 字符）。
 *   <li>digest 输入采用 {@code (type, nameLen:4B, name, valueLen:4B, value)} 的长度前缀帧； 每个复合对象的每个
 *       字段必须单独成帧，绝不依赖分隔字符或 NUL 拼接，因此字段值包含 NUL 也不会与跨字段拼接碰撞。
 *   <li>版本字段独立标记，用于将来在不破坏旧 key 的前提下增量调整算法。
 *   <li>输入：版本、sessionId、provider/model resource ID、providerType、Provider modelId、连续 leading SYSTEM
 *       messages 的完整合法 typed contents（每个 content 独立成帧），以及按请求顺序的 tool
 *       name/description/inputSchemaJson。
 * </ul>
 */
public final class PromptCacheAffinityKeyFactory {

  private static final String VERSION = "pc1";

  /**
   * 为单次请求派生 affinity key。sessionId 必须为正整数。
   *
   * @param sessionId 数据库 session 资源 ID，正整数
   * @param request 不可为 null，且其 model 不能为空
   * @return 形如 {@code pc1-<Base64URL-无padding>} 的字符串
   */
  public String create(long sessionId, ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    ModelDescriptor model = Objects.requireNonNull(request.model(), "request.model()");
    List<ProviderMessage> messages =
        Objects.requireNonNull(request.messages(), "request.messages()");
    List<ProviderToolDefinition> tools = Objects.requireNonNull(request.tools(), "request.tools()");
    byte[] digest = digest(sessionId, model, messages, tools);
    return VERSION + "-" + encode(digest);
  }

  private static byte[] digest(
      long sessionId,
      ModelDescriptor model,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools) {
    MessageDigest md = newMessageDigest();
    writeField(md, 'V', "version", VERSION);
    writeField(md, 'S', "sessionId", Long.toString(sessionId));
    writeField(md, 'P', "providerResourceId", Long.toString(model.providerResourceId()));
    writeField(md, 'M', "modelResourceId", Long.toString(model.modelResourceId()));
    writeField(md, 'T', "providerType", model.providerType().name());
    writeField(md, 'I', "modelId", model.modelId());
    writeLeadingSystemMessages(md, messages);
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

  private static void writeLeadingSystemMessages(MessageDigest md, List<ProviderMessage> messages) {
    int leadingSystem = countLeadingSystem(messages);
    writeField(md, 'C', "leadingSystemCount", Integer.toString(leadingSystem));
    for (int messageIndex = 0; messageIndex < leadingSystem; messageIndex++) {
      ProviderMessage message = messages.get(messageIndex);
      writeField(md, 'R', "role@" + messageIndex, message.role().name());
      List<ProviderContentBlock> contents = message.contents();
      writeField(md, 'N', "contentCount@" + messageIndex, Integer.toString(contents.size()));
      for (int blockIndex = 0; blockIndex < contents.size(); blockIndex++) {
        writeContent(md, messageIndex, blockIndex, contents.get(blockIndex));
      }
    }
  }

  private static int countLeadingSystem(List<ProviderMessage> messages) {
    int count = 0;
    for (ProviderMessage message : messages) {
      if (message.role() != ProviderMessageRole.SYSTEM) {
        break;
      }
      count++;
    }
    return count;
  }

  private static void writeContent(
      MessageDigest md, int messageIndex, int blockIndex, ProviderContentBlock block) {
    String tag = blockFieldTag(messageIndex, blockIndex);
    // 只展开 leading SYSTEM 合法 6 类内容块。ToolCall/ToolResult 由 ProviderMessage 验证约束在
    // ASSISTANT/TOOL message 中出现，因此这里遇到它们属于协议违规，交给 default 显式失败。
    if (block instanceof ProviderTextBlock text) {
      writeField(md, 'B', tag + "/TEXT/text", text.text());
    } else if (block instanceof ProviderImageBlock image) {
      writeField(md, 'B', tag + "/IMAGE/mediaType", image.mediaType());
      writeField(md, 'B', tag + "/IMAGE/source", image.source());
    } else if (block instanceof ProviderAudioBlock audio) {
      writeField(md, 'B', tag + "/AUDIO/mediaType", audio.mediaType());
      writeField(md, 'B', tag + "/AUDIO/source", audio.source());
    } else if (block instanceof ProviderVideoBlock video) {
      writeField(md, 'B', tag + "/VIDEO/mediaType", video.mediaType());
      writeField(md, 'B', tag + "/VIDEO/source", video.source());
    } else if (block instanceof ProviderThinkingBlock thinking) {
      writeField(md, 'B', tag + "/THINKING/thinking", thinking.thinking());
    } else if (block instanceof ProviderJsonBlock json) {
      writeField(md, 'B', tag + "/JSON/json", json.json());
    } else {
      throw new IllegalStateException(
          "leading SYSTEM contents must be one of TEXT/IMAGE/AUDIO/VIDEO/THINKING/JSON, got: "
              + block.getClass().getSimpleName());
    }
  }

  private static String blockFieldTag(int messageIndex, int blockIndex) {
    return "msg#" + messageIndex + "/block#" + blockIndex;
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
