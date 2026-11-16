package fun.fengwk.kkstudio.harness.codec;

import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.array;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.object;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.orderedSet;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.readEnum;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.requireFields;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.model.HarnessJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 共享的 {@link AgentMessage} 与 6 类 durable {@link AgentMessageContent} 严格、确定性 JSON codec。
 *
 * <p>字段固定且顺序确定；content 通过 {@code type} discriminator 区分。codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON
 * null；trailing token（共享 {@link ObjectMapper} 启用 {@link
 * DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用 {@link
 * StreamReadFeature#STRICT_DUPLICATE_DETECTION}）；未知枚举；未知 content discriminator；raw {@code
 * argumentsJson} / {@code detailsJson} 非单一 JSON object；raw {@code json} 非单一 JSON value；{@code
 * tool_result} 嵌套 {@code tool_call} 或 {@code tool_result}。encode 端同样严格校验 raw JSON 字段。
 *
 * <p>durable 内容类型包含 text / thinking / redacted_thinking / json / tool_call / tool_result /
 * resource（resource 是唯一 durable 媒体引用：blobId/name/totalBytes/totalLines/preview）。Assistant block
 * 可携带其 Provider 身份与原生字段。瞬时 Provider 投影类型 Image / Audio / Video（携带 attempt-only source）在 encode 与
 * decode 两端都确定性拒绝，绝不进入持久化 JSON。
 *
 * <p>只提供 node API：Entry payload 与 Thread command payload codec 在自己的信封里嵌放 message 子树。
 */
public final class AgentMessageJsonCodec {

  static final ObjectMapper MAPPER = HarnessJson.STRICT;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> MESSAGE_FIELDS = orderedSet("role", "contents");

  public AgentMessageJsonCodec() {}

  /** 把 {@link AgentMessage} 编码为 deterministic canonical {@link ObjectNode}。 */
  public ObjectNode encodeNode(AgentMessage message) {
    Objects.requireNonNull(message, "message");
    ObjectNode node = NODES.objectNode();
    node.put("role", message.role().name());
    ArrayNode contents = node.putArray("contents");
    for (AgentMessageContent content : message.contents()) {
      contents.add(AgentMessageContentJsonCodec.encodeContent(content));
    }
    return node;
  }

  /**
   * 把 {@link AgentMessage} 编码为 client command 请求的 canonical {@link ObjectNode}：与 {@link
   * #encodeNode} 相同，但允许瞬时 {@link AttachmentMessageContent}（编码为 {@code
   * {"type":"attachment","uploadId":...,"imageTier":...}}）。 请求 hash 只基于该形态计算，绝不进入 durable payload。
   */
  public ObjectNode encodeRequestNode(AgentMessage message) {
    Objects.requireNonNull(message, "message");
    ObjectNode node = NODES.objectNode();
    node.put("role", message.role().name());
    ArrayNode contents = node.putArray("contents");
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof AttachmentMessageContent attachment) {
        ObjectNode item = contents.addObject();
        item.put("type", "attachment");
        item.put("uploadId", attachment.uploadId().toString());
        AgentMessageContentJsonCodec.putNullableText(
            item, "imageTier", AgentMessageContentJsonCodec.wireName(attachment.imageTier()));
        continue;
      }
      contents.add(AgentMessageContentJsonCodec.encodeContent(content));
    }
    return node;
  }

  /** 从任意 {@link JsonNode} 解码 {@link AgentMessage}。 */
  public AgentMessage decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    ObjectNode node = object(value, "message");
    requireFields(node, MESSAGE_FIELDS, "message");
    AgentMessageRole role =
        readEnum(
            AgentMessageRole.class,
            AgentMessageContentJsonCodec.text(node, "role"),
            "message.role");
    ArrayNode contentsNode = array(node.get("contents"), "message.contents");
    List<AgentMessageContent> contents = new ArrayList<>(contentsNode.size());
    for (JsonNode element : contentsNode) {
      contents.add(AgentMessageContentJsonCodec.decodeContent(element));
    }
    return new AgentMessage(role, contents);
  }
}
