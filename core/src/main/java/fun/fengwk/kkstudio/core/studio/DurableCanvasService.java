package fun.fengwk.kkstudio.core.studio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasCommandDedupMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasCommandDedupDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasLinkDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodeKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.NodeTransform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable Canvas command/query 服务。
 *
 * <p>支持：列出快照、创建 canvas、应用一个小型命令集（{@code create_text_node}、 {@code create_generate_text_node}、{@code
 * create_link}、{@code move_nodes}、 {@code delete_node}）。Revision CAS 在 Canvas document 行上强制实施；幂等以
 * {@code (canvasId, commandId)} 为键，{@code request_hash} 由服务端计算为精确 UTF-8 commands JSON 的 SHA-256。
 *
 * <p>当前提供的 Node Function 是 {@code system.generate-text} v1；{@code dataJson} payload 使用注入的 Jackson
 * {@link ObjectMapper} 构建而非字符串拼接，因此内嵌引号、控制字符与换行都能安全往返。
 */
public class DurableCanvasService implements CanvasQueryService, CanvasCommandService {

  private static final String SYSTEM_GENERATE_TEXT = "system.generate-text";

  private static final String SYSTEM_GENERATE_TEXT_VERSION = "1";

  private static final String DEFAULT_VIEWPORT = "{\"x\":80,\"y\":20,\"scale\":0.6}";

  private final CanvasDocumentMapper documentMapper;
  private final CanvasNodeMapper nodeMapper;
  private final CanvasLinkMapper linkMapper;
  private final CanvasCommandDedupMapper commandDedupMapper;
  private final ObjectMapper objectMapper;
  private final PostgresqlSequenceIdGenerator idGenerator;

  public DurableCanvasService(
      CanvasDocumentMapper documentMapper,
      CanvasNodeMapper nodeMapper,
      CanvasLinkMapper linkMapper,
      CanvasCommandDedupMapper commandDedupMapper,
      ObjectMapper objectMapper,
      PostgresqlSequenceIdGenerator idGenerator) {
    this.documentMapper = documentMapper;
    this.nodeMapper = nodeMapper;
    this.linkMapper = linkMapper;
    this.commandDedupMapper = commandDedupMapper;
    this.objectMapper = objectMapper;
    this.idGenerator = idGenerator;
  }

  @Override
  public Optional<CanvasSnapshot> findSnapshot(long canvasId) {
    CanvasDocumentDO document = documentMapper.getById(canvasId);
    if (document == null) {
      return Optional.empty();
    }
    return Optional.of(toSnapshot(document));
  }

  @Override
  public List<CanvasDocument> listDocuments() {
    List<CanvasDocument> result = new ArrayList<>();
    for (CanvasDocumentDO document : documentMapper.listAll()) {
      result.add(toDocument(document));
    }
    return result;
  }

  @Override
  @Transactional
  public CanvasDocument createCanvas(String title) {
    String normalized = title == null ? "" : title.trim();
    if (normalized.isEmpty()) {
      normalized = "未命名画布";
    }
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(idGenerator.next());
    document.setTitle(normalized);
    document.setRevision(0L);
    document.setHomeViewportJson(DEFAULT_VIEWPORT);
    documentMapper.insert(document);
    return toDocument(documentMapper.getById(document.getId()));
  }

  @Override
  @Transactional
  public CanvasSnapshot applyCommands(
      long canvasId, long baseRevision, String commandId, String commandsJson) {
    if (commandId == null || commandId.isBlank()) {
      throw new IllegalArgumentException("commandId must not be blank");
    }
    if (commandsJson == null || commandsJson.isBlank()) {
      throw new IllegalArgumentException("commandsJson must not be blank");
    }
    JsonNode root = parseCommands(commandsJson);
    if (!root.isArray()) {
      throw new IllegalArgumentException("commandsJson must be a JSON array");
    }
    if (root.isEmpty()) {
      throw new IllegalArgumentException("commandsJson must contain at least one command");
    }

    String requestHash = sha256Hex(commandsJson);

    CanvasDocumentDO document = requireDocument(canvasId);
    CanvasCommandDedupDO existing = commandDedupMapper.findById(canvasId, commandId);
    if (existing != null) {
      if (!requestHash.equals(existing.getRequestHash())) {
        throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
      }
      return findSnapshot(canvasId).orElseThrow();
    }
    if (!Objects.equals(document.getRevision(), baseRevision)) {
      throw new IllegalStateException("REVISION_CONFLICT");
    }

    long nextRevision = baseRevision + 1;
    for (JsonNode command : root) {
      String type = text(command, "type");
      switch (type) {
        case "create_text_node" -> createTextNode(canvasId, command);
        case "create_generate_text_node" -> createGenerateTextNode(canvasId, command);
        case "create_link" -> createLink(canvasId, command);
        case "move_nodes" -> moveNodes(canvasId, command);
        case "delete_node" -> deleteNode(canvasId, command);
        default -> throw new IllegalArgumentException("Unsupported command type: " + type);
      }
    }

    int updated =
        documentMapper.updateRevisionAndTitle(
            canvasId,
            baseRevision,
            nextRevision,
            document.getTitle(),
            document.getHomeViewportJson());
    if (updated != 1) {
      throw new IllegalStateException("REVISION_CONFLICT");
    }

    CanvasSnapshot snapshot = findSnapshot(canvasId).orElseThrow();
    CanvasCommandDedupDO record = new CanvasCommandDedupDO();
    record.setCanvasId(canvasId);
    record.setCommandId(commandId);
    record.setRequestHash(requestHash);
    commandDedupMapper.insert(record);
    return snapshot;
  }

  private void createTextNode(long canvasId, JsonNode command) {
    double x = command.path("x").asDouble(120d);
    double y = command.path("y").asDouble(120d);
    double width = command.path("width").asDouble(240d);
    double height = command.path("height").asDouble(120d);
    String name = optionalText(command, "name", "文本");
    String body = optionalText(command, "text", "");
    NodeTransform transform = validateTransform("text node", x, y, width, height);
    ObjectNode data = objectMapper.createObjectNode();
    data.put("text", body);
    CanvasNodeDO node = baseNode(canvasId);
    node.setKind(CanvasNodeKind.RESOURCE.name());
    node.setNodeType("text");
    node.setName(name);
    node.setX(transform.x());
    node.setY(transform.y());
    node.setWidth(transform.width());
    node.setHeight(transform.height());
    node.setDataJson(writeJson(data));
    nodeMapper.insert(node);
  }

  private void createGenerateTextNode(long canvasId, JsonNode command) {
    double x = command.path("x").asDouble(420d);
    double y = command.path("y").asDouble(120d);
    double width = command.path("width").asDouble(280d);
    double height = command.path("height").asDouble(180d);
    String name = optionalText(command, "name", "文本生成");
    String prompt = optionalText(command, "prompt", "");
    NodeTransform transform = validateTransform("generate-text node", x, y, width, height);
    ObjectNode data = objectMapper.createObjectNode();
    data.put("functionId", SYSTEM_GENERATE_TEXT);
    data.put("version", SYSTEM_GENERATE_TEXT_VERSION);
    data.put("prompt", prompt);
    data.put("configRevision", 0);
    CanvasNodeDO node = baseNode(canvasId);
    node.setKind(CanvasNodeKind.FUNCTION.name());
    node.setNodeType(SYSTEM_GENERATE_TEXT);
    node.setName(name);
    node.setX(transform.x());
    node.setY(transform.y());
    node.setWidth(transform.width());
    node.setHeight(transform.height());
    node.setDataJson(writeJson(data));
    nodeMapper.insert(node);
  }

  private void createLink(long canvasId, JsonNode command) {
    long source = command.path("sourceNodeId").asLong(0L);
    long target = command.path("targetNodeId").asLong(0L);
    if (source <= 0L || target <= 0L) {
      throw new IllegalArgumentException("create_link requires positive sourceNodeId/targetNodeId");
    }
    if (source == target) {
      throw new IllegalArgumentException("create_link requires distinct sourceNodeId/targetNodeId");
    }
    if (nodeMapper.getById(canvasId, source) == null
        || nodeMapper.getById(canvasId, target) == null) {
      throw new IllegalArgumentException("create_link nodes must exist in the same canvas");
    }
    CanvasLinkDO link = new CanvasLinkDO();
    link.setId(idGenerator.next());
    link.setCanvasId(canvasId);
    link.setSourceNodeId(source);
    link.setTargetNodeId(target);
    linkMapper.insert(link);
  }

  private void moveNodes(long canvasId, JsonNode command) {
    JsonNode updates = command.path("updates");
    if (!updates.isArray()) {
      throw new IllegalArgumentException("move_nodes.updates must be an array");
    }
    for (JsonNode update : updates) {
      long id = update.path("id").asLong(0L);
      CanvasNodeDO node = nodeMapper.getById(canvasId, id);
      if (node == null) {
        throw new IllegalArgumentException("Unknown node: " + id);
      }
      double x = update.path("x").asDouble(node.getX());
      double y = update.path("y").asDouble(node.getY());
      NodeTransform transform =
          validateTransform("move_nodes update", x, y, node.getWidth(), node.getHeight());
      node.setX(transform.x());
      node.setY(transform.y());
      nodeMapper.updatePosition(node);
    }
  }

  private void deleteNode(long canvasId, JsonNode command) {
    long id = command.path("id").asLong(0L);
    if (nodeMapper.deleteById(canvasId, id) != 1) {
      throw new IllegalArgumentException("Unknown node: " + id);
    }
  }

  private CanvasNodeDO baseNode(long canvasId) {
    CanvasNodeDO node = new CanvasNodeDO();
    node.setId(idGenerator.next());
    node.setCanvasId(canvasId);
    return node;
  }

  private CanvasDocumentDO requireDocument(long canvasId) {
    CanvasDocumentDO document = documentMapper.getById(canvasId);
    if (document == null) {
      throw new IllegalArgumentException("Canvas not found: " + canvasId);
    }
    return document;
  }

  private CanvasSnapshot toSnapshot(CanvasDocumentDO document) {
    List<CanvasNode> nodes = new ArrayList<>();
    for (CanvasNodeDO node : nodeMapper.listByCanvas(document.getId())) {
      nodes.add(toNode(node));
    }
    List<CanvasLink> links = new ArrayList<>();
    for (CanvasLinkDO link : linkMapper.listByCanvas(document.getId())) {
      links.add(new CanvasLink(link.getId(), link.getSourceNodeId(), link.getTargetNodeId()));
    }
    return new CanvasSnapshot(toDocument(document), nodes, links);
  }

  private static CanvasDocument toDocument(CanvasDocumentDO document) {
    return new CanvasDocument(
        document.getId(),
        document.getTitle(),
        document.getRevision(),
        document.getHomeViewportJson());
  }

  private static CanvasNode toNode(CanvasNodeDO node) {
    NodeTransform transform =
        new NodeTransform(node.getX(), node.getY(), node.getWidth(), node.getHeight());
    return new CanvasNode(
        node.getId(),
        CanvasNodeKind.valueOf(node.getKind()),
        node.getNodeType(),
        node.getName(),
        transform,
        node.getDataJson());
  }

  private JsonNode parseCommands(String commandsJson) {
    try {
      return objectMapper.readTree(commandsJson);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("commandsJson must be valid JSON", ex);
    }
  }

  private String writeJson(ObjectNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("data JSON serialization failed", ex);
    }
  }

  private static NodeTransform validateTransform(
      String label, double x, double y, double width, double height) {
    try {
      return new NodeTransform(x, y, width, height);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(label + ": " + ex.getMessage(), ex);
    }
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field, String defaultValue) {
    String value = node.path(field).asText(null);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
