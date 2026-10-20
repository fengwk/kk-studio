package fun.fengwk.kkstudio.harness.runtime;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
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
 * <p>哈希覆盖 target 语义、预分配 ID（session/thread/start entry）、NEW_SESSION 的 root settings + parent + YOLO
 * policy，以及 ordered {@code (idempotencyKey, requestHash)} 对；同一 raw 请求永远得到同一 hash，不同内容（含 id
 * 或命令顺序变化）得到不同 hash。YOLO 只包含创建请求携带的执行根开关（子代理恒为 false，其执行根由不可变父关系决定）；根后续 toggle 不改变既有创建请求的身份。该 hash
 * 只作持久化身份键，不对产品 DTO 暴露。
 */
public final class ThreadCreationRequestHash {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private ThreadCreationRequestHash() {}

  /** 计算 NEW_SESSION target 的 creation request hash。 */
  public static String forNewSession(
      UUID sessionId,
      UUID threadId,
      BranchSettings rootSettings,
      UUID parentThreadId,
      boolean yoloEnabled,
      SpawnTrigger trigger,
      ThreadConstraints constraints,
      List<NewThreadCommand> commands) {
    ObjectNode envelope = envelope("NEW_SESSION");
    envelope.put("sessionId", requireId(sessionId, "sessionId").toString());
    envelope.put("threadId", requireId(threadId, "threadId").toString());
    envelope.set("root", rootNode(rootSettings));
    if (parentThreadId == null) {
      envelope.putNull("parentThreadId");
    } else {
      envelope.put("parentThreadId", parentThreadId.toString());
    }
    envelope.put("yoloEnabled", yoloEnabled);
    envelope.put("trigger", requireNonNull(trigger, "trigger").name());
    envelope.set("constraints", constraintsNode(constraints));
    envelope.set("commands", commandsNode(commands));
    return digest(envelope);
  }

  /**
   * 计算 NEW_THREAD target 的 creation request hash。{@code threadName} 必须已经 {@link
   * Names#normalize}，因为分支显示名是创建请求 身份的一部分：同 threadId + 同规范化名称精确 replay，改名则 hash 变化并映射为 ID reuse 冲突。
   */
  public static String forNewThread(
      UUID sessionId,
      UUID startEntryId,
      UUID threadId,
      String threadName,
      boolean yoloEnabled,
      List<NewThreadCommand> commands) {
    ObjectNode envelope = envelope("NEW_THREAD");
    envelope.put("sessionId", requireId(sessionId, "sessionId").toString());
    envelope.put("startEntryId", requireId(startEntryId, "startEntryId").toString());
    envelope.put("threadId", requireId(threadId, "threadId").toString());
    envelope.put("threadName", requireNonNull(threadName, "threadName"));
    envelope.put("yoloEnabled", yoloEnabled);
    envelope.set("commands", commandsNode(commands));
    return digest(envelope);
  }

  /**
   * 计算 NEW_FORKED_SESSION target 的 creation request hash：来源 Thread、切点、新 Session/Thread、YOLO 开关 与
   * ordered 命令对。同一 raw 请求稳定得到同一 hash，内容变化（含切点或命令顺序）得到不同 hash。
   */
  public static String forNewForkedSession(
      UUID sourceThreadId,
      UUID startEntryId,
      UUID sessionId,
      UUID threadId,
      boolean yoloEnabled,
      List<NewThreadCommand> commands) {
    ObjectNode envelope = envelope("NEW_FORKED_SESSION");
    envelope.put("sourceThreadId", requireId(sourceThreadId, "sourceThreadId").toString());
    envelope.put("startEntryId", requireId(startEntryId, "startEntryId").toString());
    envelope.put("sessionId", requireId(sessionId, "sessionId").toString());
    envelope.put("threadId", requireId(threadId, "threadId").toString());
    envelope.put("yoloEnabled", yoloEnabled);
    envelope.set("commands", commandsNode(commands));
    return digest(envelope);
  }

  private static ObjectNode envelope(String target) {
    ObjectNode envelope = NODES.objectNode();
    envelope.put("target", target);
    return envelope;
  }

  /** root settings 完全复用 ROOT Entry 的 canonical codec，保证与 durable 形态一致。 */
  private static JsonNode rootNode(BranchSettings rootSettings) {
    RootPayload root = new RootPayload(requireNonNull(rootSettings, "rootSettings"));
    String canonical = new HistoryEntryPayloadJsonCodec().encode(root);
    try {
      return MAPPER.readTree(canonical);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot parse canonical root payload", error);
    }
  }

  /** 请求约束（合并父约束之前）进入创建身份：同一请求以不同约束重放视为 ID 复用冲突。 */
  private static ObjectNode constraintsNode(ThreadConstraints constraints) {
    requireNonNull(constraints, "constraints");
    ObjectNode node = NODES.objectNode();
    if (constraints.maxOutputTokens() == null) {
      node.putNull("maxOutputTokens");
    } else {
      node.put("maxOutputTokens", constraints.maxOutputTokens());
    }
    node.put("promptCacheDisabled", constraints.promptCacheDisabled());
    node.put("allowAutoCompaction", constraints.allowAutoCompaction());
    return node;
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
