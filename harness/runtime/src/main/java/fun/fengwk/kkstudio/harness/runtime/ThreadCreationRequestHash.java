package fun.fengwk.kkstudio.harness.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 服务端 creation request hash：NEW_SESSION / NEW_THREAD 初始创建请求在持久化 Thread 上的 64 位小写 SHA-256 身份键。
 *
 * <p>哈希覆盖 target 语义、预分配 ID（session/thread/start entry）、NEW_SESSION 的 root settings + subagent +
 * yolo，以及 ordered {@code (idempotencyKey, requestHash)} 对；同一 raw 请求永远得到同一 hash，不同内容（含 id
 * 或命令顺序变化）得到不同 hash。该 hash 只作持久化身份键，不对产品 DTO 暴露。
 */
public final class ThreadCreationRequestHash {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private ThreadCreationRequestHash() {}

  /** 计算 NEW_SESSION target 的 creation request hash。 */
  public static String forNewSession(
      UUID sessionId,
      UUID threadId,
      BranchSettings rootSettings,
      SubagentContext subagentContext,
      boolean yoloEnabled,
      List<NewThreadCommand> commands) {
    ObjectNode envelope = envelope("NEW_SESSION");
    envelope.put("sessionId", requireId(sessionId, "sessionId").toString());
    envelope.put("threadId", requireId(threadId, "threadId").toString());
    envelope.set("root", rootNode(rootSettings, subagentContext));
    envelope.put("yolo", yoloEnabled);
    envelope.set("commands", commandsNode(commands));
    return digest(envelope);
  }

  /** 计算 NEW_THREAD target 的 creation request hash。 */
  public static String forNewThread(
      UUID sessionId,
      UUID startEntryId,
      UUID threadId,
      boolean yoloEnabled,
      List<NewThreadCommand> commands) {
    ObjectNode envelope = envelope("NEW_THREAD");
    envelope.put("sessionId", requireId(sessionId, "sessionId").toString());
    envelope.put("startEntryId", requireId(startEntryId, "startEntryId").toString());
    envelope.put("threadId", requireId(threadId, "threadId").toString());
    envelope.put("yolo", yoloEnabled);
    envelope.set("commands", commandsNode(commands));
    return digest(envelope);
  }

  private static ObjectNode envelope(String target) {
    ObjectNode envelope = NODES.objectNode();
    envelope.put("target", target);
    return envelope;
  }

  /** root settings + subagent 完全复用 ROOT Entry 的 canonical codec，保证与 durable 形态一致。 */
  private static JsonNode rootNode(BranchSettings rootSettings, SubagentContext subagentContext) {
    RootPayload root =
        new RootPayload(requireNonNull(rootSettings, "rootSettings"), subagentContext);
    String canonical = new HistoryEntryPayloadJsonCodec().encode(root);
    try {
      return MAPPER.readTree(canonical);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot parse canonical root payload", error);
    }
  }

  private static ArrayNode commandsNode(List<NewThreadCommand> commands) {
    requireNonNull(commands, "commands");
    ArrayNode node = NODES.arrayNode();
    for (NewThreadCommand command : commands) {
      Objects.requireNonNull(command, "commands[]");
      ObjectNode entry = NODES.objectNode();
      entry.put("idempotencyKey", command.idempotencyKey().toString());
      entry.put("requestHash", command.requestHash());
      node.add(entry);
    }
    return node;
  }

  private static String digest(ObjectNode envelope) {
    byte[] canonical = envelope.toString().getBytes(StandardCharsets.UTF_8);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  private static <T> T requireNonNull(T value, String field) {
    return Objects.requireNonNull(value, field);
  }

  private static Object requireId(Object value, String field) {
    return Objects.requireNonNull(value, field);
  }
}
