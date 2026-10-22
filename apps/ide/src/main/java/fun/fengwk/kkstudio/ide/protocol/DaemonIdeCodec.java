package fun.fengwk.kkstudio.ide.protocol;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * IDE 控制消息 payload 的严格 codec：{@code IDE_COMMAND} 承载 {@link IdeCommand}，{@code IDE_RESULT} 承载 {@link
 * IdeReply}。
 *
 * <p>与其它 daemon payload 一样：拒绝未知字段、重复键与尾随内容，并把语义校验失败统一包成 {@link
 * DaemonProtocolException}。可空字段在编码时省略（与契约中 {@code optional} 字段一致），解码时按缺失处理。
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
          "directory",
          "ticket",
          "streamUrl",
          "mode",
          "method",
          "path",
          "headers");

  private static final Set<String> REPLY_FIELDS =
      Set.of("ok", "viewId", "directory", "workspacePath", "message");

  public String encodeCommand(IdeCommand command) {
    Objects.requireNonNull(command, "command");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("action", command.action().name());
    root.put("viewId", command.viewId());
    putOptional(root, "directory", command.directory());
    putOptional(root, "ticket", command.ticket());
    putOptional(root, "streamUrl", command.streamUrl());
    if (command.mode() != null) {
      root.put("mode", command.mode().name());
    }
    putOptional(root, "method", command.method());
    putOptional(root, "path", command.path());
    if (!command.headers().isEmpty()) {
      ArrayNode headers = root.putArray("headers");
      for (IdeHeader header : command.headers()) {
        ArrayNode pair = headers.addArray();
        pair.add(header.name());
        pair.add(header.value());
      }
    }
    return write(root, "ide command");
  }

  public String encodeReply(IdeReply reply) {
    Objects.requireNonNull(reply, "reply");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("ok", reply.ok());
    putOptional(root, "viewId", reply.viewId());
    putOptional(root, "directory", reply.directory());
    putOptional(root, "workspacePath", reply.workspacePath());
    putOptional(root, "message", reply.message());
    return write(root, "ide reply");
  }

  public IdeCommand decodeCommand(String json) {
    ObjectNode root = readObject(json, "ide command");
    rejectUnknownFields(root, COMMAND_FIELDS, "ide command");
    String actionText = requiredText(root, "action", "ide command");
    IdeAction action;
    try {
      action = IdeAction.valueOf(actionText);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("unknown ide action: " + actionText, error);
    }
    String viewId = optionalText(root, "viewId", "ide command");
    String directory = optionalText(root, "directory", "ide command");
    String ticket = optionalText(root, "ticket", "ide command");
    String streamUrl = optionalText(root, "streamUrl", "ide command");
    String modeText = optionalText(root, "mode", "ide command");
    IdeStreamMode mode = null;
    if (modeText != null) {
      try {
        mode = IdeStreamMode.valueOf(modeText);
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException("unknown ide stream mode: " + modeText, error);
      }
    }
    String method = optionalText(root, "method", "ide command");
    String path = optionalText(root, "path", "ide command");
    List<IdeHeader> headers = decodeHeaders(root);
    try {
      return new IdeCommand(
          action, viewId, directory, ticket, streamUrl, mode, method, path, headers);
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
    String viewId = optionalText(root, "viewId", "ide reply");
    String directory = optionalText(root, "directory", "ide reply");
    String workspacePath = optionalText(root, "workspacePath", "ide reply");
    String message = optionalText(root, "message", "ide reply");
    try {
      return new IdeReply(okNode.booleanValue(), viewId, directory, workspacePath, message);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "ide reply validation failed: " + error.getMessage(), error);
    }
  }

  private List<IdeHeader> decodeHeaders(ObjectNode root) {
    JsonNode headers = root.get("headers");
    if (headers == null || headers.isNull()) {
      return List.of();
    }
    if (!headers.isArray()) {
      throw new DaemonProtocolException("ide command.headers must be an array of [name,value]");
    }
    List<IdeHeader> decoded = new ArrayList<>();
    for (JsonNode entry : headers) {
      if (!entry.isArray()
          || entry.size() != 2
          || !entry.get(0).isString()
          || !entry.get(1).isString()) {
        throw new DaemonProtocolException("ide command.headers entry must be [name,value] strings");
      }
      try {
        decoded.add(new IdeHeader(entry.get(0).stringValue(), entry.get(1).stringValue()));
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "ide command header is invalid: " + error.getMessage(), error);
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
    root.propertyNames()
        .forEach(
            field -> {
              if (!allowed.contains(field)) {
                throw new DaemonProtocolException(context + " has unknown field: " + field);
              }
            });
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

  private static String write(ObjectNode root, String context) {
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new DaemonProtocolException("cannot encode " + context + " payload", error);
    }
  }
}
