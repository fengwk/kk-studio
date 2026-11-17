package fun.fengwk.kkstudio.harness.api.join;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.hash.Sha256;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable protocol value for a task delegation accepted by a subagent runner.
 *
 * <p>The invocation id is the stable identity for one task call. The runner must use it as an
 * idempotency key and return the same child Thread for retries of that call.
 *
 * @param invocationId current task Tool invocation UUID (stable idempotency identity)
 * @param parentThreadId parent Thread UUID that initiated the delegation
 * @param prompt complete task prompt (authoritative parent instruction)
 * @param subagentType target subagent name
 * @param maxTurns soft turn budget, or null to use the policy default
 * @param resumeThreadId existing child Thread UUID to continue, or null for a new delegation
 */
public record SubagentTaskRequest(
    UUID invocationId,
    UUID parentThreadId,
    String prompt,
    String subagentType,
    Integer maxTurns,
    UUID resumeThreadId) {

  private static final String CHILD_THREAD_NAMESPACE = "kk-studio/harness/subagent/thread/";

  public SubagentTaskRequest {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("prompt must not be blank");
    }
    if (subagentType == null || subagentType.isBlank()) {
      throw new IllegalArgumentException("subagentType must not be blank");
    }
    if (maxTurns != null && maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive when provided");
    }
  }

  /** Parse and validate the stable arguments carried by a task tool call. */
  public static SubagentTaskRequest parseArguments(
      ObjectMapper objectMapper, UUID invocationId, UUID parentThreadId, ToolCall call) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    Objects.requireNonNull(call, "call");
    JsonNode value;
    try {
      value = objectMapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
    } catch (JacksonException error) {
      throw new IllegalArgumentException("task arguments are not valid JSON", error);
    }
    if (!(value instanceof ObjectNode node)) {
      throw new IllegalArgumentException("task arguments must be a JSON object");
    }
    String subagentType = requiredText(node, "subagent_type");
    String prompt = requiredText(node, "prompt");
    Integer maxTurns = null;
    JsonNode maxTurnsNode = node.get("max_turns");
    if (maxTurnsNode != null && !maxTurnsNode.isNull()) {
      if (!maxTurnsNode.isIntegralNumber() || !maxTurnsNode.canConvertToInt()) {
        throw new IllegalArgumentException("max_turns must be a positive integer");
      }
      maxTurns = maxTurnsNode.intValue();
      if (maxTurns < 1) {
        throw new IllegalArgumentException("max_turns must be a positive integer");
      }
    }
    UUID threadId = optionalUuid(node, "thread_id");
    return new SubagentTaskRequest(
        invocationId, parentThreadId, prompt, subagentType, maxTurns, threadId);
  }

  /**
   * Stable target child Thread identity: derive fresh work from the invocation, or retain the
   * resumed Thread.
   */
  public UUID childThreadId() {
    return resumeThreadId == null
        ? UUID.nameUUIDFromBytes(
            (CHILD_THREAD_NAMESPACE + invocationId).getBytes(StandardCharsets.UTF_8))
        : resumeThreadId;
  }

  /**
   * Request fingerprint matching the durable Join; omitted and explicit maxTurns remain distinct.
   */
  public String requestHash() {
    MessageDigest digest = Sha256.newDigest();
    List<String> parts =
        List.of(
            parentThreadId.toString(),
            subagentType,
            prompt,
            maxTurns == null ? "default" : "explicit:" + maxTurns,
            resumeThreadId == null ? "new" : "resume:" + resumeThreadId);
    for (String part : parts) {
      byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
      digest.update(
          new byte[] {
            (byte) (bytes.length >>> 24),
            (byte) (bytes.length >>> 16),
            (byte) (bytes.length >>> 8),
            (byte) bytes.length
          });
      digest.update(bytes);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Validate that a durable task invocation and Join describe the same accepted delegation. */
  public static void requireMatchingJoin(ToolInvocation invocation, ThreadJoin join) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(join, "join");
    if (!"task".equals(invocation.call().toolName())) {
      throw new IllegalStateException("child-wait state belongs to a non-task invocation");
    }
    SubagentTaskRequest request;
    try {
      request =
          parseArguments(
              new ObjectMapper(), invocation.id(), invocation.threadId(), invocation.call());
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("persisted task invocation has invalid arguments", invalid);
    }
    if (!join.invocationId().equals(invocation.id())
        || !Objects.equals(join.parentThreadId(), invocation.threadId())
        || join.delivery() != JoinDelivery.SETTLE_TOOL_RESULT
        || !join.childThreadId().equals(request.childThreadId())
        || !join.requestHash().equals(request.requestHash())
        || !join.agent().equals(request.subagentType())
        || (request.maxTurns() != null && !request.maxTurns().equals(join.maxTurns()))) {
      throw new IllegalStateException(
          "task Join does not match its durable Tool invocation " + invocation.id());
    }
  }

  /** Rebuild the original task tool result from its durable matched Join receipt. */
  public static ToolResult resultFromJoin(
      ObjectMapper objectMapper,
      String callId,
      UUID sessionId,
      ThreadJoin join,
      ThreadJoinReceipt receipt) {
    boolean error = receipt.outcome() != ThreadJoinOutcome.COMPLETED;
    String status =
        switch (receipt.outcome()) {
          case COMPLETED -> "completed";
          case ERROR -> "failed";
          case CANCELLED -> "cancelled";
        };
    String text =
        switch (receipt.outcome()) {
          case COMPLETED -> receipt.report();
          case ERROR -> failureText("Subagent failed", receipt);
          case CANCELLED -> failureText("Subagent cancelled", receipt);
        };
    if (!error && (text == null || text.isBlank())) {
      status = "failed";
      error = true;
      text = "Subagent ended without a final answer.";
    }
    ObjectNode details = objectMapper.createObjectNode();
    details.put("kind", "task.result");
    details.put("thread_id", join.childThreadId().toString());
    details.put("status", status);
    details.put("session_id", sessionId.toString());
    details.put("subagent_type", join.agent());
    details.put("replayed", false);
    return new ToolResult(
        callId,
        List.of(new TextResultContent(text == null ? "" : text)),
        error,
        details.toString());
  }

  private static String failureText(String prefix, ThreadJoinReceipt receipt) {
    StringBuilder text = new StringBuilder(prefix).append(": ");
    text.append(
        receipt.error() == null || receipt.error().isBlank()
            ? "no reason provided"
            : receipt.error());
    if (receipt.partialResult() != null && !receipt.partialResult().isBlank()) {
      text.append("\nPartial result:\n").append(receipt.partialResult());
    }
    return text.toString();
  }

  private static UUID optionalUuid(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException(field + " must be a canonical UUID string");
    }
    try {
      return Uuids.parseCanonical(value.stringValue(), field);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID string", error);
    }
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.stringValue().isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    String text = value.stringValue();
    if (field.equals("subagent_type")) {
      if (!text.equals(text.strip())) {
        throw new IllegalArgumentException("subagent_type must not contain surrounding whitespace");
      }
      return text;
    }
    return text.strip();
  }
}
