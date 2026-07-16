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
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
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
 *   <li>digest 输入采用长度前缀编码（type + ":" + 字段名长度 + ":" + 字段名 + ":" + 值长度 + ":" + 值 + "\n"），
 *       字段边界不可碰撞，绝不可能以简单字符串拼接伪造同 key。
 *   <li>版本字段独立标记，用于将来在不破坏旧 key 的前提下增量调整算法。
 *   <li>输入：版本、sessionId、provider/model resource ID、providerType、Provider modelId、连续 leading SYSTEM
 *       messages 的完整 typed contents、按请求顺序的 tool name/description/inputSchemaJson。
 * </ul>
 */
public final class PromptCacheAffinityKeyFactory {

  private static final String VERSION = "pc1";

  /** 可被测试直接调用的工具函数：把单个 ProviderContentBlock 的稳定字节写入 digest。 */
  static void writeContentForTest(
      MessageDigest md, int messageIndex, int blockIndex, ProviderContentBlock block) {
    writeContent(md, messageIndex, blockIndex, block);
  }

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
      // 每条 system message 单独写入 role 与 contents，避免共享边界造成拼接碰撞。
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
    if (block instanceof ProviderTextBlock text) {
      writeField(md, 'B', blockFieldTag(messageIndex, blockIndex, "TEXT"), text.text());
    } else if (block instanceof ProviderImageBlock image) {
      writeField(
          md,
          'B',
          blockFieldTag(messageIndex, blockIndex, "IMAGE"),
          image.mediaType() + "\u0000" + image.source());
    } else if (block instanceof ProviderAudioBlock audio) {
      writeField(
          md,
          'B',
          blockFieldTag(messageIndex, blockIndex, "AUDIO"),
          audio.mediaType() + "\u0000" + audio.source());
    } else if (block instanceof ProviderVideoBlock video) {
      writeField(
          md,
          'B',
          blockFieldTag(messageIndex, blockIndex, "VIDEO"),
          video.mediaType() + "\u0000" + video.source());
    } else if (block instanceof ProviderThinkingBlock thinking) {
      writeField(md, 'B', blockFieldTag(messageIndex, blockIndex, "THINKING"), thinking.thinking());
    } else if (block instanceof ProviderJsonBlock json) {
      writeField(md, 'B', blockFieldTag(messageIndex, blockIndex, "JSON"), json.json());
    } else if (block instanceof ProviderToolCallBlock toolCall) {
      writeField(
          md,
          'B',
          blockFieldTag(messageIndex, blockIndex, "TOOL_CALL"),
          toolCall.toolCall().id()
              + "\u0000"
              + toolCall.toolCall().name()
              + "\u0000"
              + toolCall.toolCall().argumentsJson());
    } else if (block instanceof ProviderToolResultBlock toolResult) {
      writeField(
          md,
          'B',
          blockFieldTag(messageIndex, blockIndex, "TOOL_RESULT"),
          toolResult.toolCallId()
              + "\u0000"
              + toolResult.toolName()
              + "\u0000"
              + Boolean.toString(toolResult.error())
              + "\u0000"
              + toolResult.detailsJson()
              + "\u0000"
              + Integer.toString(toolResult.contents().size()));
    } else {
      throw new IllegalStateException("unhandled provider content block: " + block.getClass());
    }
  }

  private static String blockFieldTag(int messageIndex, int blockIndex, String kind) {
    return "msg#" + messageIndex + "/block#" + blockIndex + "/" + kind;
  }

  private static void writeTools(MessageDigest md, List<ProviderToolDefinition> tools) {
    writeField(md, 'L', "toolCount", Integer.toString(tools.size()));
    for (int index = 0; index < tools.size(); index++) {
      ProviderToolDefinition tool = tools.get(index);
      writeField(md, 'O', "tool#" + index + "/name", tool.name());
      writeField(md, 'D', "tool#" + index + "/description", tool.description());
      writeField(md, 'J', "tool#" + index + "/inputSchemaJson", tool.inputSchemaJson());
    }
  }

  /**
   * 写入一条带有类型标记、字段名长度与内容长度的字段。type | 字段名长度 | 字段名 | 值长度 | 值，每段以 ":" 定界并以 "\\n" 收尾，足以对抗任何简单字符串拼接碰撞。
   */
  private static void writeField(MessageDigest md, char type, String name, String value) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(value, "value");
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
    md.update((byte) type);
    md.update(colon());
    md.update(lengthPrefix(nameBytes.length));
    md.update(colon());
    md.update(nameBytes);
    md.update(colon());
    md.update(lengthPrefix(valueBytes.length));
    md.update(colon());
    md.update(valueBytes);
    md.update(newline());
  }

  private static byte[] colon() {
    return new byte[] {0x3A};
  }

  private static byte[] newline() {
    return new byte[] {0x0A};
  }

  private static byte[] lengthPrefix(int length) {
    if (length < 0) {
      throw new IllegalArgumentException("length must be non-negative");
    }
    String hex = HexFormat.of().toHexDigits(length);
    // Pad to fixed 8 hex characters (32-bit) so that consecutive fields have unambiguous
    // boundaries.
    if (hex.length() < 8) {
      hex = "0".repeat(8 - hex.length()) + hex;
    }
    return hex.getBytes(StandardCharsets.UTF_8);
  }
}
