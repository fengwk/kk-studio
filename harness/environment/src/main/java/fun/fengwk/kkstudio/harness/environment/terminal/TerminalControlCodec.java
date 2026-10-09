package fun.fengwk.kkstudio.harness.environment.terminal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.BoundedJsonWriter;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 终端控制协议的唯一 canonical JSON codec。
 *
 * <p>它只在一个入口解析 JSON、只用一套同类型读写函数处理模型：command 根 {@code
 * {version,requestId,environmentId,viewerId,type,payload}}，event 根 {@code
 * {version,requestId,environmentId,viewerId,identity,type,payload}}，request/response 只有一层 {@code
 * {route,command}}/{@code {route,event}} 包装，内层复用同一 command/event 模型，不定义第二套 RPC 格式。{@link
 * TerminalEvent.Type#VIEW_UPDATE} 直接嵌入既有 {@link TerminalViewUpdateCodec} 的 JSON object，不复制格子算法。
 *
 * <p>解码前先施加总 UTF-8 字节上限并拒绝重复 key、尾随文本、缺失/未知字段、未知 type、错误版本、错误 null、非整数/溢出整数、非 canonical UUID 与
 * Base64；出错一律转为固定描述的 {@link TerminalControlException}，不回显输入、token、executable 或画面，也不保留原始 Jackson
 * 异常。编码同样有界，超过 {@link TerminalLimits#MAX_MESSAGE_BYTES} 时明确失败。
 */
public final class TerminalControlCodec {

  /** 唯一的控制协议 wire 版本。 */
  public static final int VERSION = 1;

  private static final Set<String> COMMAND_FIELDS =
      Set.of("version", "requestId", "environmentId", "viewerId", "type", "payload");
  private static final Set<String> EVENT_FIELDS =
      Set.of("version", "requestId", "environmentId", "viewerId", "identity", "type", "payload");
  private static final Set<String> REQUEST_FIELDS = Set.of("route", "command");
  private static final Set<String> RESPONSE_FIELDS = Set.of("route", "event");
  private static final Set<String> ROUTE_FIELDS = Set.of("appNodeId", "connectionId");
  private static final Set<String> IDENTITY_FIELDS = Set.of("daemonInstanceId", "terminalId");
  private static final Set<String> GRANT_FIELDS = Set.of("epoch", "token");
  private static final Set<String> RECOVERY_FIELDS = Set.of("previous", "seq", "digest");
  private static final Set<String> WRITER_STATE_FIELDS =
      Set.of(
          "writerEpoch",
          "lastWrittenSeq",
          "lastWrittenDigest",
          "lastResolvedSeq",
          "lastResolvedDigest",
          "lastResolvedOutcome",
          "pendingSeq",
          "pendingDigest",
          "frozen");
  private static final Set<String> CONTROL_RESULT_FIELDS =
      Set.of("status", "grant", "recovered", "reason");
  private static final Set<String> ADMISSION_RESULT_FIELDS =
      Set.of("kind", "seq", "digest", "outcome", "reason");

  private static final Pattern DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");

  private static final String PARSE_ERROR = "terminal control message must be valid strict JSON";
  private static final String INVALID_ERROR = "terminal control message is invalid";
  private static final String SIZE_ERROR =
      "terminal control message exceeds the terminal message budget";

  /** 1..4096 字节 canonical padded Base64 的文本长度下界（1 字节解码为 4 字符）。 */
  private static final int MIN_INPUT_BASE64_LENGTH = 4;

  /** 4096 字节解码后 canonical padded Base64 的文本长度上界：{@code ((4096 + 2) / 3) * 4 = 5464}。 */
  private static final int MAX_INPUT_BASE64_LENGTH =
      ((TerminalCommand.MAX_INPUT_BYTES + 2) / 3) * 4;

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper(
          JsonFactory.builder()
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxStringLength(TerminalLimits.MAX_MESSAGE_BYTES)
                      .maxNestingDepth(100)
                      .maxNumberLength(1000)
                      .maxDocumentLength(TerminalLimits.MAX_MESSAGE_BYTES)
                      .build())
              .build());

  private static final TerminalViewUpdateCodec VIEW_CODEC = new TerminalViewUpdateCodec();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** 编码命令为 canonical JSON。 */
  public String encodeCommand(TerminalCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }
    return write(encodeCommandNode(command));
  }

  /** 解码并严格校验命令。 */
  public TerminalCommand decodeCommand(String json) {
    return guarded(() -> decodeCommandNode(readObject(json)));
  }

  /** 编码事件为 canonical JSON。 */
  public String encodeEvent(TerminalEvent event) {
    if (event == null) {
      throw new IllegalArgumentException("event must not be null");
    }
    return write(encodeEventNode(event));
  }

  /** 解码并严格校验事件。 */
  public TerminalEvent decodeEvent(String json) {
    return guarded(() -> decodeEventNode(readObject(json)));
  }

  /** 编码请求（route + command）为 canonical JSON。 */
  public String encodeRequest(TerminalRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.set("route", encodeRoute(request.route()));
    root.set("command", encodeCommandNode(request.command()));
    return write(root);
  }

  /** 解码并严格校验请求。 */
  public TerminalRequest decodeRequest(String json) {
    return guarded(
        () -> {
          JsonNode root = readObject(json);
          rejectUnknownFields(root, REQUEST_FIELDS);
          requireFields(root, REQUEST_FIELDS);
          TerminalRoute route = decodeRoute(requireObject(root.get("route")));
          TerminalCommand command = decodeCommandNode(requireObject(root.get("command")));
          return new TerminalRequest(route, command);
        });
  }

  /** 编码响应（route + event）为 canonical JSON。 */
  public String encodeResponse(TerminalResponse response) {
    if (response == null) {
      throw new IllegalArgumentException("response must not be null");
    }
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.set("route", encodeRoute(response.route()));
    root.set("event", encodeEventNode(response.event()));
    return write(root);
  }

  /** 解码并严格校验响应。 */
  public TerminalResponse decodeResponse(String json) {
    return guarded(
        () -> {
          JsonNode root = readObject(json);
          rejectUnknownFields(root, RESPONSE_FIELDS);
          requireFields(root, RESPONSE_FIELDS);
          TerminalRoute route = decodeRoute(requireObject(root.get("route")));
          TerminalEvent event = decodeEventNode(requireObject(root.get("event")));
          return new TerminalResponse(route, event);
        });
  }

  // ---------------------------------------------------------------------
  // Encode
  // ---------------------------------------------------------------------

  private static ObjectNode encodeCommandNode(TerminalCommand command) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", VERSION);
    root.put("requestId", command.requestId().toString());
    root.put("environmentId", command.environmentId().toString());
    root.put("viewerId", command.viewerId().toString());
    root.put("type", command.payload().type().name());
    root.set("payload", encodeCommandPayload(command.payload()));
    return root;
  }

  private static ObjectNode encodeCommandPayload(TerminalCommand.Payload payload) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    switch (payload) {
      case TerminalCommand.Open open -> putIdentity(node, "expectedExited", open.expectedExited());
      case TerminalCommand.Attach attach -> node.set("identity", encodeIdentity(attach.identity()));
      case TerminalCommand.Detach detach -> {
        node.set("identity", encodeIdentity(detach.identity()));
        node.put("streamId", detach.streamId().toString());
      }
      case TerminalCommand.Claim claim -> {
        node.set("identity", encodeIdentity(claim.identity()));
        node.put("streamId", claim.streamId().toString());
        putRecovery(node, "recovery", claim.recovery());
      }
      case TerminalCommand.Takeover takeover -> {
        node.set("identity", encodeIdentity(takeover.identity()));
        node.put("streamId", takeover.streamId().toString());
        putUuid(node, "expectedWriterEpoch", takeover.expectedWriterEpoch());
      }
      case TerminalCommand.Release release -> {
        node.set("identity", encodeIdentity(release.identity()));
        node.put("streamId", release.streamId().toString());
        node.set("grant", encodeGrant(release.grant()));
      }
      case TerminalCommand.Input input -> {
        node.set("identity", encodeIdentity(input.identity()));
        node.put("streamId", input.streamId().toString());
        node.set("grant", encodeGrant(input.grant()));
        node.put("seq", input.seq());
        node.put("inputModeRevision", input.inputModeRevision());
        node.put("bytes", Base64.getEncoder().encodeToString(input.bytes()));
      }
      case TerminalCommand.Resize resize -> {
        node.set("identity", encodeIdentity(resize.identity()));
        node.put("streamId", resize.streamId().toString());
        node.set("grant", encodeGrant(resize.grant()));
        node.put("seq", resize.seq());
        node.put("cols", resize.cols());
        node.put("rows", resize.rows());
      }
      case TerminalCommand.ViewApplied viewApplied -> {
        node.set("identity", encodeIdentity(viewApplied.identity()));
        node.put("streamId", viewApplied.streamId().toString());
        node.put("version", viewApplied.version());
      }
      case TerminalCommand.Keepalive keepalive -> {
        node.set("identity", encodeIdentity(keepalive.identity()));
        node.put("streamId", keepalive.streamId().toString());
        if (keepalive.grant() == null) {
          node.putNull("grant");
        } else {
          node.set("grant", encodeGrant(keepalive.grant()));
        }
      }
      case TerminalCommand.Close close -> {
        node.set("identity", encodeIdentity(close.identity()));
        putUuid(node, "expectedWriterEpoch", close.expectedWriterEpoch());
      }
    }
    return node;
  }

  private static ObjectNode encodeEventNode(TerminalEvent event) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("version", VERSION);
    putUuid(root, "requestId", event.requestId());
    root.put("environmentId", event.environmentId().toString());
    root.put("viewerId", event.viewerId().toString());
    putIdentity(root, "identity", event.identity());
    root.put("type", event.payload().type().name());
    root.set("payload", encodeEventPayload(event.payload()));
    return root;
  }

  private static ObjectNode encodeEventPayload(TerminalEvent.Payload payload) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    switch (payload) {
      case TerminalEvent.Attached attached -> {
        node.put("streamId", attached.streamId().toString());
        node.put("executable", attached.executable());
        node.put("status", attached.status().name());
        putInteger(node, "exitCode", attached.exitCode());
        node.put("inputModeRevision", attached.inputModeRevision());
        node.set("writer", encodeWriterState(attached.writer()));
      }
      case TerminalEvent.WriterChanged writerChanged -> {
        node.set("writer", encodeWriterState(writerChanged.writer()));
        if (writerChanged.result() == null) {
          node.putNull("result");
        } else {
          node.set("result", encodeControlResult(writerChanged.result()));
        }
      }
      case TerminalEvent.OpAck opAck -> {
        node.put("writerEpoch", opAck.writerEpoch().toString());
        node.set("result", encodeAdmissionResult(opAck.result()));
        putEnum(node, "code", opAck.code());
      }
      case TerminalEvent.ViewUpdate viewUpdate -> node.set(
          "update", readNode(VIEW_CODEC.encode(viewUpdate.update())));
      case TerminalEvent.Exited exited -> {
        node.put("status", exited.status().name());
        putInteger(node, "exitCode", exited.exitCode());
      }
      case TerminalEvent.ErrorPayload error -> {
        node.put("code", error.code().name());
        node.put("disposition", error.disposition().name());
      }
    }
    return node;
  }

  private static ObjectNode encodeRoute(TerminalRoute route) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("appNodeId", route.appNodeId().toString());
    node.put("connectionId", route.connectionId());
    return node;
  }

  private static ObjectNode encodeIdentity(TerminalIdentity identity) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("daemonInstanceId", identity.daemonInstanceId().toString());
    node.put("terminalId", identity.terminalId().toString());
    return node;
  }

  private static ObjectNode encodeGrant(WriterGrant grant) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("epoch", grant.epoch().toString());
    node.put("token", grant.token().toString());
    return node;
  }

  private static ObjectNode encodeRecovery(Recovery recovery) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.set("previous", encodeGrant(recovery.previous()));
    node.put("seq", recovery.seq());
    putDigest(node, "digest", recovery.digest());
    return node;
  }

  private static ObjectNode encodeWriterState(WriterState state) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    putUuid(node, "writerEpoch", state.writerEpoch());
    node.put("lastWrittenSeq", state.lastWrittenSeq());
    putDigest(node, "lastWrittenDigest", state.lastWrittenDigest());
    node.put("lastResolvedSeq", state.lastResolvedSeq());
    putDigest(node, "lastResolvedDigest", state.lastResolvedDigest());
    putEnum(node, "lastResolvedOutcome", state.lastResolvedOutcome());
    node.put("pendingSeq", state.pendingSeq());
    putDigest(node, "pendingDigest", state.pendingDigest());
    node.put("frozen", state.frozen());
    return node;
  }

  private static ObjectNode encodeControlResult(ControlResult result) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("status", result.status().name());
    if (result.grant() == null) {
      node.putNull("grant");
    } else {
      node.set("grant", encodeGrant(result.grant()));
    }
    putEnum(node, "recovered", result.recovered());
    putEnum(node, "reason", result.reason());
    return node;
  }

  private static ObjectNode encodeAdmissionResult(AdmissionResult result) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("kind", result.kind().name());
    node.put("seq", result.seq());
    putDigest(node, "digest", result.digest());
    putEnum(node, "outcome", result.outcome());
    putEnum(node, "reason", result.reason());
    return node;
  }

  // ---------------------------------------------------------------------
  // Decode
  // ---------------------------------------------------------------------

  private static TerminalCommand decodeCommandNode(JsonNode root) {
    rejectUnknownFields(root, COMMAND_FIELDS);
    requireFields(root, COMMAND_FIELDS);
    requireVersion(root);
    UUID requestId = requiredUuid(root, "requestId");
    UUID environmentId = requiredUuid(root, "environmentId");
    UUID viewerId = requiredUuid(root, "viewerId");
    TerminalCommand.Type type = enumValue(TerminalCommand.Type.class, requiredText(root, "type"));
    TerminalCommand.Payload payload =
        decodeCommandPayload(type, requireObject(root.get("payload")));
    return new TerminalCommand(requestId, environmentId, viewerId, payload);
  }

  private static TerminalCommand.Payload decodeCommandPayload(
      TerminalCommand.Type type, JsonNode node) {
    return switch (type) {
      case OPEN -> {
        rejectUnknownFields(node, Set.of("expectedExited"));
        requireFields(node, Set.of("expectedExited"));
        yield new TerminalCommand.Open(optionalIdentity(node.get("expectedExited")));
      }
      case ATTACH -> {
        rejectUnknownFields(node, Set.of("identity"));
        requireFields(node, Set.of("identity"));
        yield new TerminalCommand.Attach(requiredIdentity(node, "identity"));
      }
      case DETACH -> {
        rejectUnknownFields(node, Set.of("identity", "streamId"));
        requireFields(node, Set.of("identity", "streamId"));
        yield new TerminalCommand.Detach(
            requiredIdentity(node, "identity"), requiredUuid(node, "streamId"));
      }
      case CLAIM -> {
        rejectUnknownFields(node, Set.of("identity", "streamId", "recovery"));
        requireFields(node, Set.of("identity", "streamId", "recovery"));
        yield new TerminalCommand.Claim(
            requiredIdentity(node, "identity"),
            requiredUuid(node, "streamId"),
            optionalRecovery(node.get("recovery")));
      }
      case TAKEOVER -> {
        rejectUnknownFields(node, Set.of("identity", "streamId", "expectedWriterEpoch"));
        requireFields(node, Set.of("identity", "streamId", "expectedWriterEpoch"));
        yield new TerminalCommand.Takeover(
            requiredIdentity(node, "identity"),
            requiredUuid(node, "streamId"),
            optionalUuid(node.get("expectedWriterEpoch")));
      }
      case RELEASE -> {
        rejectUnknownFields(node, Set.of("identity", "streamId", "grant"));
        requireFields(node, Set.of("identity", "streamId", "grant"));
        yield new TerminalCommand.Release(
            requiredIdentity(node, "identity"),
            requiredUuid(node, "streamId"),
            requiredGrant(node, "grant"));
      }
      case INPUT -> {
        rejectUnknownFields(
            node, Set.of("identity", "streamId", "grant", "seq", "inputModeRevision", "bytes"));
        requireFields(
            node, Set.of("identity", "streamId", "grant", "seq", "inputModeRevision", "bytes"));
        yield new TerminalCommand.Input(
            requiredIdentity(node, "identity"),
            requiredUuid(node, "streamId"),
            requiredGrant(node, "grant"),
            requiredPositive(rootValue(node, "seq")),
            requiredPositive(rootValue(node, "inputModeRevision")),
            requiredBytes(node, "bytes"));
      }
      case RESIZE -> {
        rejectUnknownFields(node, Set.of("identity", "streamId", "grant", "seq", "cols", "rows"));
        requireFields(node, Set.of("identity", "streamId", "grant", "seq", "cols", "rows"));
        yield new TerminalCommand.Resize(
            requiredIdentity(node, "identity"),
            requiredUuid(node, "streamId"),
            requiredGrant(node, "grant"),
            requiredPositive(rootValue(node, "seq")),
            requiredInt(node, "cols"),
            requiredInt(node, "rows"));
      }
      case VIEW_APPLIED -> {
        rejectUnknownFields(node, Set.of("identity", "streamId", "version"));
        requireFields(node, Set.of("identity", "streamId", "version"));
        yield new TerminalCommand.ViewApplied(
            requiredIdentity(node, "identity"),
            requiredUuid(node, "streamId"),
            requiredPositive(rootValue(node, "version")));
      }
      case KEEPALIVE -> {
        rejectUnknownFields(node, Set.of("identity", "streamId", "grant"));
        requireFields(node, Set.of("identity", "streamId", "grant"));
        yield new TerminalCommand.Keepalive(
            requiredIdentity(node, "identity"),
            requiredUuid(node, "streamId"),
            optionalGrant(node.get("grant")));
      }
      case CLOSE -> {
        rejectUnknownFields(node, Set.of("identity", "expectedWriterEpoch"));
        requireFields(node, Set.of("identity", "expectedWriterEpoch"));
        yield new TerminalCommand.Close(
            requiredIdentity(node, "identity"), optionalUuid(node.get("expectedWriterEpoch")));
      }
    };
  }

  private static TerminalEvent decodeEventNode(JsonNode root) {
    rejectUnknownFields(root, EVENT_FIELDS);
    requireFields(root, EVENT_FIELDS);
    requireVersion(root);
    UUID requestId = optionalUuid(root.get("requestId"));
    UUID environmentId = requiredUuid(root, "environmentId");
    UUID viewerId = requiredUuid(root, "viewerId");
    TerminalIdentity identity = optionalIdentity(root.get("identity"));
    TerminalEvent.Type type = enumValue(TerminalEvent.Type.class, requiredText(root, "type"));
    TerminalEvent.Payload payload = decodeEventPayload(type, requireObject(root.get("payload")));
    return new TerminalEvent(requestId, environmentId, viewerId, identity, payload);
  }

  private static TerminalEvent.Payload decodeEventPayload(TerminalEvent.Type type, JsonNode node) {
    return switch (type) {
      case ATTACHED -> {
        rejectUnknownFields(
            node,
            Set.of("streamId", "executable", "status", "exitCode", "inputModeRevision", "writer"));
        requireFields(
            node,
            Set.of("streamId", "executable", "status", "exitCode", "inputModeRevision", "writer"));
        yield new TerminalEvent.Attached(
            requiredUuid(node, "streamId"),
            requiredText(node, "executable"),
            enumValue(TerminalStatus.class, requiredText(node, "status")),
            optionalInteger(node, "exitCode"),
            requiredPositive(rootValue(node, "inputModeRevision")),
            requiredWriterState(node, "writer"));
      }
      case WRITER_CHANGED -> {
        rejectUnknownFields(node, Set.of("writer", "result"));
        requireFields(node, Set.of("writer", "result"));
        yield new TerminalEvent.WriterChanged(
            requiredWriterState(node, "writer"), optionalControlResult(node.get("result")));
      }
      case OP_ACK -> {
        rejectUnknownFields(node, Set.of("writerEpoch", "result", "code"));
        requireFields(node, Set.of("writerEpoch", "result", "code"));
        yield new TerminalEvent.OpAck(
            requiredUuid(node, "writerEpoch"),
            requiredAdmissionResult(node, "result"),
            optionalEnum(ErrorCode.class, node.get("code")));
      }
      case VIEW_UPDATE -> {
        rejectUnknownFields(node, Set.of("update"));
        requireFields(node, Set.of("update"));
        yield new TerminalEvent.ViewUpdate(decodeViewUpdate(requireObject(node.get("update"))));
      }
      case EXITED -> {
        rejectUnknownFields(node, Set.of("status", "exitCode"));
        requireFields(node, Set.of("status", "exitCode"));
        yield new TerminalEvent.Exited(
            enumValue(TerminalStatus.class, requiredText(node, "status")),
            optionalInteger(node, "exitCode"));
      }
      case ERROR -> {
        rejectUnknownFields(node, Set.of("code", "disposition"));
        requireFields(node, Set.of("code", "disposition"));
        yield new TerminalEvent.ErrorPayload(
            enumValue(ErrorCode.class, requiredText(node, "code")),
            enumValue(ErrorDisposition.class, requiredText(node, "disposition")));
      }
    };
  }

  private static TerminalRoute decodeRoute(JsonNode node) {
    rejectUnknownFields(node, ROUTE_FIELDS);
    requireFields(node, ROUTE_FIELDS);
    return new TerminalRoute(requiredUuid(node, "appNodeId"), requiredText(node, "connectionId"));
  }

  private static TerminalIdentity requiredIdentity(JsonNode node, String field) {
    return decodeIdentity(requireObject(node.get(field)));
  }

  private static TerminalIdentity optionalIdentity(JsonNode node) {
    requirePresent(node);
    return node.isNull() ? null : decodeIdentity(requireObject(node));
  }

  private static TerminalIdentity decodeIdentity(JsonNode node) {
    rejectUnknownFields(node, IDENTITY_FIELDS);
    requireFields(node, IDENTITY_FIELDS);
    return new TerminalIdentity(
        requiredUuid(node, "daemonInstanceId"), requiredUuid(node, "terminalId"));
  }

  private static WriterGrant requiredGrant(JsonNode node, String field) {
    return decodeGrant(requireObject(node.get(field)));
  }

  private static WriterGrant optionalGrant(JsonNode node) {
    requirePresent(node);
    return node.isNull() ? null : decodeGrant(requireObject(node));
  }

  private static WriterGrant decodeGrant(JsonNode node) {
    rejectUnknownFields(node, GRANT_FIELDS);
    requireFields(node, GRANT_FIELDS);
    return new WriterGrant(requiredUuid(node, "epoch"), requiredUuid(node, "token"));
  }

  private static Recovery optionalRecovery(JsonNode node) {
    requirePresent(node);
    if (node.isNull()) {
      return null;
    }
    JsonNode object = requireObject(node);
    rejectUnknownFields(object, RECOVERY_FIELDS);
    requireFields(object, RECOVERY_FIELDS);
    long seq = requiredSafe(rootValue(object, "seq"));
    return new Recovery(
        decodeGrant(requireObject(object.get("previous"))),
        seq,
        optionalDigest(object.get("digest")));
  }

  private static WriterState requiredWriterState(JsonNode node, String field) {
    JsonNode object = requireObject(node.get(field));
    rejectUnknownFields(object, WRITER_STATE_FIELDS);
    requireFields(object, WRITER_STATE_FIELDS);
    return new WriterState(
        optionalUuid(object.get("writerEpoch")),
        requiredSafe(rootValue(object, "lastWrittenSeq")),
        optionalDigest(object.get("lastWrittenDigest")),
        requiredSafe(rootValue(object, "lastResolvedSeq")),
        optionalDigest(object.get("lastResolvedDigest")),
        optionalEnum(OperationOutcome.class, object.get("lastResolvedOutcome")),
        requiredSafe(rootValue(object, "pendingSeq")),
        optionalDigest(object.get("pendingDigest")),
        requiredBoolean(object, "frozen"));
  }

  private static ControlResult optionalControlResult(JsonNode node) {
    requirePresent(node);
    if (node.isNull()) {
      return null;
    }
    JsonNode object = requireObject(node);
    rejectUnknownFields(object, CONTROL_RESULT_FIELDS);
    requireFields(object, CONTROL_RESULT_FIELDS);
    return new ControlResult(
        enumValue(ControlResult.Status.class, requiredText(object, "status")),
        optionalGrant(object.get("grant")),
        optionalEnum(OperationOutcome.class, object.get("recovered")),
        optionalEnum(ControlResult.RejectReason.class, object.get("reason")));
  }

  private static AdmissionResult requiredAdmissionResult(JsonNode node, String field) {
    JsonNode object = requireObject(node.get(field));
    rejectUnknownFields(object, ADMISSION_RESULT_FIELDS);
    requireFields(object, ADMISSION_RESULT_FIELDS);
    return new AdmissionResult(
        enumValue(AdmissionResult.Kind.class, requiredText(object, "kind")),
        requiredSafe(rootValue(object, "seq")),
        optionalDigest(object.get("digest")),
        optionalEnum(OperationOutcome.class, object.get("outcome")),
        optionalEnum(AdmissionResult.RejectReason.class, object.get("reason")));
  }

  private static TerminalViewUpdate decodeViewUpdate(JsonNode node) {
    try {
      return VIEW_CODEC.decode(OBJECT_MAPPER.writeValueAsString(node));
    } catch (JsonProcessingException error) {
      throw new TerminalControlException(INVALID_ERROR);
    } catch (TerminalViewUpdateCodec.TerminalViewUpdateException error) {
      throw new TerminalControlException(INVALID_ERROR);
    }
  }

  // ---------------------------------------------------------------------
  // Low level strict readers
  // ---------------------------------------------------------------------

  private static JsonNode readObject(String json) {
    if (json == null) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    int bytes;
    try {
      bytes = ResourceRef.utf8LengthUpTo(json, "json", TerminalLimits.MAX_MESSAGE_BYTES);
    } catch (IllegalArgumentException error) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    if (bytes > TerminalLimits.MAX_MESSAGE_BYTES) {
      throw new TerminalControlException(SIZE_ERROR);
    }
    JsonNode node;
    try {
      node = OBJECT_MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new TerminalControlException(PARSE_ERROR);
    }
    if (node == null || !node.isObject()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return node;
  }

  private static String write(JsonNode root) {
    String json = BoundedJsonWriter.write(root, TerminalLimits.MAX_MESSAGE_BYTES);
    if (json == null) {
      throw new TerminalControlException(SIZE_ERROR);
    }
    return json;
  }

  private static void requireVersion(JsonNode root) {
    if (requiredIntegral(rootValue(root, "version")) != VERSION) {
      throw new TerminalControlException(INVALID_ERROR);
    }
  }

  private static UUID requiredUuid(JsonNode node, String field) {
    return decodeUuid(requiredText(node, field));
  }

  private static UUID optionalUuid(JsonNode node) {
    requirePresent(node);
    if (node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return decodeUuid(node.textValue());
  }

  private static UUID decodeUuid(String text) {
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new TerminalControlException(INVALID_ERROR);
    }
  }

  private static OperationDigest optionalDigest(JsonNode node) {
    requirePresent(node);
    if (node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    String hex = node.textValue();
    if (!DIGEST_PATTERN.matcher(hex).matches()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    byte[] bytes = new byte[OperationDigest.LENGTH];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
    }
    return OperationDigest.of(bytes);
  }

  private static byte[] requiredBytes(JsonNode node, String field) {
    String text = requiredText(node, field);
    // 先按 Base64 文本长度上界拒绝，避免在验证 1..4096 字节前解码一个超大字符串。
    if (text.length() < MIN_INPUT_BASE64_LENGTH || text.length() > MAX_INPUT_BASE64_LENGTH) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(text);
    } catch (IllegalArgumentException error) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    if (decoded.length < TerminalCommand.MIN_INPUT_BYTES
        || decoded.length > TerminalCommand.MAX_INPUT_BYTES
        || !Base64.getEncoder().encodeToString(decoded).equals(text)) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return decoded;
  }

  private static int requiredInt(JsonNode node, String field) {
    long value = requiredSafe(rootValue(node, field));
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return (int) value;
  }

  private static Integer optionalInteger(JsonNode node, String field) {
    JsonNode value = rootValue(node, field);
    if (value.isNull()) {
      return null;
    }
    long decoded = requiredIntegral(value);
    if (decoded < Integer.MIN_VALUE || decoded > Integer.MAX_VALUE) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return (int) decoded;
  }

  private static boolean requiredBoolean(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return value.booleanValue();
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = rootValue(node, field);
    if (!value.isTextual()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return value.textValue();
  }

  private static long requiredSafe(JsonNode node) {
    long value = requiredIntegral(node);
    if (value < 0 || value > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return value;
  }

  private static long requiredPositive(JsonNode node) {
    long value = requiredIntegral(node);
    if (value < 1 || value > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return value;
  }

  private static long requiredIntegral(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return node.longValue();
  }

  private static JsonNode rootValue(JsonNode node, String field) {
    JsonNode value = node.get(field);
    requirePresent(value);
    return value;
  }

  private static void requirePresent(JsonNode node) {
    if (node == null) {
      throw new TerminalControlException(INVALID_ERROR);
    }
  }

  private static JsonNode requireObject(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return node;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException error) {
      throw new TerminalControlException(INVALID_ERROR);
    }
  }

  private static <E extends Enum<E>> E optionalEnum(Class<E> type, JsonNode node) {
    requirePresent(node);
    if (node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new TerminalControlException(INVALID_ERROR);
    }
    return enumValue(type, node.textValue());
  }

  private static void rejectUnknownFields(JsonNode object, Set<String> allowed) {
    var fields = object.fieldNames();
    while (fields.hasNext()) {
      if (!allowed.contains(fields.next())) {
        throw new TerminalControlException(INVALID_ERROR);
      }
    }
  }

  private static void requireFields(JsonNode object, Set<String> required) {
    for (String field : required) {
      if (!object.has(field)) {
        throw new TerminalControlException(INVALID_ERROR);
      }
    }
  }

  // ---------------------------------------------------------------------
  // Encode helpers
  // ---------------------------------------------------------------------

  private static void putUuid(ObjectNode node, String field, UUID value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.toString());
    }
  }

  private static void putInteger(ObjectNode node, String field, Integer value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.intValue());
    }
  }

  private static void putEnum(ObjectNode node, String field, Enum<?> value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.name());
    }
  }

  private static void putDigest(ObjectNode node, String field, OperationDigest digest) {
    if (digest == null) {
      node.putNull(field);
    } else {
      node.put(field, toHex(digest.value()));
    }
  }

  private static void putIdentity(ObjectNode node, String field, TerminalIdentity identity) {
    if (identity == null) {
      node.putNull(field);
    } else {
      node.set(field, encodeIdentity(identity));
    }
  }

  private static void putRecovery(ObjectNode node, String field, Recovery recovery) {
    if (recovery == null) {
      node.putNull(field);
    } else {
      node.set(field, encodeRecovery(recovery));
    }
  }

  private static String toHex(byte[] value) {
    StringBuilder builder = new StringBuilder(value.length * 2);
    for (byte b : value) {
      builder.append(Character.forDigit((b >> 4) & 0xF, 16));
      builder.append(Character.forDigit(b & 0xF, 16));
    }
    return builder.toString();
  }

  private static JsonNode readNode(String json) {
    try {
      return OBJECT_MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new TerminalControlException(INVALID_ERROR);
    }
  }

  private static <T> T guarded(ModelSupplier<T> supplier) {
    try {
      return supplier.get();
    } catch (TerminalControlException error) {
      throw error;
    } catch (IllegalArgumentException error) {
      throw new TerminalControlException(INVALID_ERROR);
    }
  }

  /** 延迟构造的模型供应器；用于把模型不变式拒绝归一为固定协议错误。 */
  @FunctionalInterface
  private interface ModelSupplier<T> {
    T get();
  }

  /** 控制协议错误：消息固定，不携带输入、token、executable、画面或原始解析异常。 */
  public static final class TerminalControlException extends IllegalArgumentException {

    private TerminalControlException(String message) {
      super(message);
    }
  }
}
