package fun.fengwk.kkstudio.ide.protocol;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * IDE 控制消息 payload 的严格 codec：{@code IDE_COMMAND} 承载 {@link IdeCommand}，{@code IDE_RESULT} 承载 {@link
 * IdeReply}。
 *
 * <p>与其它 daemon payload 一样：拒绝未知字段、重复键与尾随内容，并把语义校验失败统一包成 {@link
 * DaemonProtocolException}。可空字段在编码时省略；{@code mode} 使用冻结的小写 wire 值。
 */
public final class DaemonIdeCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> COMMAND_FIELDS =
      Set.of(
          "action",
          "viewId",
          "documentId",
          "eventNodeId",
          "connectionId",
          "claimId",
          "bindingEpoch",
          "mode",
          "directory",
          "discard");

  private static final Set<String> REPLY_FIELDS =
      Set.of(
          "ok",
          "viewId",
          "documentId",
          "eventNodeId",
          "connectionId",
          "claimId",
          "bindingEpoch",
          "directory",
          "workspacePath",
          "views",
          "code",
          "message");

  private static final Set<String> VIEW_FIELDS =
      Set.of(
          "viewId",
          "documentId",
          "eventNodeId",
          "connectionId",
          "claimId",
          "bindingEpoch",
          "directory",
          "workspacePath",
          "state");

  public String encodeCommand(IdeCommand command) {
    Objects.requireNonNull(command, "command");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("action", command.action().name());
    putOptional(root, "viewId", command.viewId());
    putOptional(root, "documentId", command.documentId());
    putOptional(root, "eventNodeId", command.eventNodeId());
    putOptional(root, "connectionId", command.connectionId());
    putOptional(root, "claimId", command.claimId());
    putOptional(root, "bindingEpoch", epochText(command.bindingEpoch()));
    putOptional(root, "mode", command.mode() == null ? null : command.mode().wireValue());
    putOptional(root, "directory", command.directory());
    if (command.discard() != null) {
      root.put("discard", command.discard());
    }
    return write(root, "ide command");
  }

  public String encodeReply(IdeReply reply) {
    Objects.requireNonNull(reply, "reply");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("ok", reply.ok());
    putOptional(root, "viewId", uuidText(reply.viewId()));
    putOptional(root, "documentId", uuidText(reply.documentId()));
    putOptional(root, "eventNodeId", uuidText(reply.eventNodeId()));
    putOptional(root, "connectionId", uuidText(reply.connectionId()));
    putOptional(root, "claimId", uuidText(reply.claimId()));
    putOptional(root, "bindingEpoch", epochText(reply.bindingEpoch()));
    putOptional(root, "directory", reply.directory());
    putOptional(root, "workspacePath", reply.workspacePath());
    if (reply.views() != null) {
      ArrayNode views = root.putArray("views");
      for (IdeViewSummary view : reply.views()) {
        ObjectNode node = views.addObject();
        node.put("viewId", view.viewId().toString());
        node.put("documentId", view.documentId().toString());
        node.put("eventNodeId", view.eventNodeId().toString());
        node.put("connectionId", view.connectionId().toString());
        node.put("claimId", view.claimId().toString());
        node.put("bindingEpoch", Long.toString(view.bindingEpoch()));
        node.put("directory", view.directory());
        node.put("workspacePath", view.workspacePath());
        node.put("state", view.state().name());
      }
    }
    putOptional(root, "code", reply.code());
    putOptional(root, "message", reply.message());
    return write(root, "ide reply");
  }

  public IdeCommand decodeCommand(String json) {
    ObjectNode root = readObject(json, "ide command");
    rejectUnknownFields(root, COMMAND_FIELDS, "ide command");
    IdeAction action = decodeAction(requiredText(root, "action", "ide command"));
    String viewId = optionalText(root, "viewId", "ide command");
    String documentId = optionalText(root, "documentId", "ide command");
    String eventNodeId = optionalText(root, "eventNodeId", "ide command");
    String connectionId = optionalText(root, "connectionId", "ide command");
    String claimId = optionalText(root, "claimId", "ide command");
    Long bindingEpoch = optionalEpoch(root, "bindingEpoch", "ide command");
    IdeOpenMode mode = optionalMode(root);
    String directory = optionalText(root, "directory", "ide command");
    Boolean discard = optionalBoolean(root, "discard", "ide command");
    try {
      return new IdeCommand(
          action,
          viewId,
          documentId,
          eventNodeId,
          connectionId,
          claimId,
          bindingEpoch,
          mode,
          directory,
          discard);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "ide command validation failed: " + error.getMessage(), error);
    }
  }

  public IdeReply decodeReply(String json) {
    ObjectNode root = readObject(json, "ide reply");
    rejectUnknownFields(root, REPLY_FIELDS, "ide reply");
    JsonNode okNode = root.get("ok");
    if (okNode == null || !okNode.isBoolean()) {
      throw new DaemonProtocolException("ide reply.ok must be a boolean");
    }
    UUID viewId = optionalUuid(root, "viewId", "ide reply");
    UUID documentId = optionalUuid(root, "documentId", "ide reply");
    UUID eventNodeId = optionalUuid(root, "eventNodeId", "ide reply");
    UUID connectionId = optionalUuid(root, "connectionId", "ide reply");
    UUID claimId = optionalUuid(root, "claimId", "ide reply");
    Long bindingEpoch = optionalEpoch(root, "bindingEpoch", "ide reply");
    String directory = optionalText(root, "directory", "ide reply");
    String workspacePath = optionalText(root, "workspacePath", "ide reply");
    List<IdeViewSummary> views = decodeViews(root);
    String code = optionalText(root, "code", "ide reply");
    String message = optionalText(root, "message", "ide reply");
    try {
      return new IdeReply(
          okNode.booleanValue(),
          viewId,
          documentId,
          eventNodeId,
          connectionId,
          claimId,
          bindingEpoch,
          directory,
          workspacePath,
          views,
          code,
          message);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "ide reply validation failed: " + error.getMessage(), error);
    }
  }

  private static IdeAction decodeAction(String value) {
    try {
      return IdeAction.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("unknown ide action: " + value, error);
    }
  }

  private static IdeOpenMode optionalMode(ObjectNode root) {
    String modeText = optionalText(root, "mode", "ide command");
    if (modeText == null) {
      return null;
    }
    try {
      return IdeOpenMode.fromWire(modeText);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("unknown ide open mode: " + modeText, error);
    }
  }

  private static List<IdeViewSummary> decodeViews(ObjectNode root) {
    JsonNode views = root.get("views");
    if (views == null || views.isNull()) {
      return null;
    }
    if (!views.isArray()) {
      throw new DaemonProtocolException("ide reply.views must be an array");
    }
    if (views.size() > 64) {
      throw new DaemonProtocolException("ide reply.views must not exceed 64 entries");
    }
    List<IdeViewSummary> decoded = new ArrayList<>(views.size());
    for (JsonNode item : views) {
      if (!(item instanceof ObjectNode view)) {
        throw new DaemonProtocolException("ide reply.views entry must be an object");
      }
      rejectUnknownFields(view, VIEW_FIELDS, "ide reply.views entry");
      try {
        decoded.add(
            new IdeViewSummary(
                requiredUuid(view, "viewId", "ide reply.views entry"),
                requiredUuid(view, "documentId", "ide reply.views entry"),
                requiredUuid(view, "eventNodeId", "ide reply.views entry"),
                requiredUuid(view, "connectionId", "ide reply.views entry"),
                requiredUuid(view, "claimId", "ide reply.views entry"),
                requiredEpoch(view, "bindingEpoch", "ide reply.views entry"),
                requiredText(view, "directory", "ide reply.views entry"),
                requiredText(view, "workspacePath", "ide reply.views entry"),
                IdeViewState.fromWire(requiredText(view, "state", "ide reply.views entry"))));
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "ide reply view validation failed: " + error.getMessage(), error);
      }
    }
    return decoded;
  }

  private static ObjectNode readObject(String json, String context) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("malformed " + context + " payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException(context + " payload must be an object");
    }
    return root;
  }

  private static void rejectUnknownFields(ObjectNode root, Set<String> allowed, String context) {
    for (String field : root.propertyNames()) {
      if (!allowed.contains(field)) {
        throw new DaemonProtocolException(context + " has unknown field: " + field);
      }
    }
  }

  private static void putOptional(ObjectNode root, String field, String value) {
    if (value != null) {
      root.put(field, value);
    }
  }

  private static String requiredText(ObjectNode root, String field, String context) {
    String value = optionalText(root, field, context);
    if (value == null) {
      throw new DaemonProtocolException(context + "." + field + " is required");
    }
    return value;
  }

  private static String optionalText(ObjectNode root, String field, String context) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new DaemonProtocolException(context + "." + field + " must be text");
    }
    return value.stringValue();
  }

  private static UUID requiredUuid(ObjectNode root, String field, String context) {
    UUID value = optionalUuid(root, field, context);
    if (value == null) {
      throw new DaemonProtocolException(context + "." + field + " is required");
    }
    return value;
  }

  private static UUID optionalUuid(ObjectNode root, String field, String context) {
    String text = optionalText(root, field, context);
    if (text == null) {
      return null;
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(text);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(context + "." + field + " must be a UUID", error);
    }
    if (!parsed.toString().equals(text)) {
      throw new DaemonProtocolException(
          context + "." + field + " must be a canonical lowercase UUID");
    }
    return parsed;
  }

  private static long requiredEpoch(ObjectNode root, String field, String context) {
    Long value = optionalEpoch(root, field, context);
    if (value == null) {
      throw new DaemonProtocolException(context + "." + field + " is required");
    }
    return value;
  }

  private static Long optionalEpoch(ObjectNode root, String field, String context) {
    String text = optionalText(root, field, context);
    if (text == null) {
      return null;
    }
    try {
      return DecimalLongs.parsePositive(text, field);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          context + "." + field + " must be a canonical positive decimal", error);
    }
  }

  private static Boolean optionalBoolean(ObjectNode root, String field, String context) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isBoolean()) {
      throw new DaemonProtocolException(context + "." + field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static String epochText(Long value) {
    return value == null ? null : Long.toString(value);
  }

  private static String uuidText(UUID value) {
    return value == null ? null : value.toString();
  }

  private static String write(ObjectNode root, String context) {
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode " + context + " payload", error);
    }
  }
}
