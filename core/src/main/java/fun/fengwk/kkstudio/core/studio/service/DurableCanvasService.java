package fun.fengwk.kkstudio.core.studio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasCommandMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasCommandDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasLinkDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.studio.StudioWorkspaces;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasLifecycle;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodeKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceReference;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.NodeTransform;
import fun.fengwk.kkstudio.studio.canvas.NodeValidity;
import fun.fengwk.kkstudio.studio.runtime.SystemFunctionIds;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal durable Canvas command/query service.
 *
 * <p>Supports document CRUD-ish flow and a tiny command set for text / generate-text nodes and
 * visibility links. ResourceReference and real FunctionRun remain future work.
 */
public class DurableCanvasService implements CanvasQueryService, CanvasCommandService {

  private static final String DEFAULT_VIEWPORT = "{\"x\":80,\"y\":20,\"scale\":0.6}";
  private static final int SCHEMA_VERSION = 1;

  private final CanvasDocumentMapper documentMapper;
  private final CanvasNodeMapper nodeMapper;
  private final CanvasLinkMapper linkMapper;
  private final CanvasCommandMapper commandMapper;
  private final ObjectMapper objectMapper;
  private final PostgresqlSequenceIdGenerator idGenerator;

  public DurableCanvasService(
      CanvasDocumentMapper documentMapper,
      CanvasNodeMapper nodeMapper,
      CanvasLinkMapper linkMapper,
      CanvasCommandMapper commandMapper,
      ObjectMapper objectMapper,
      PostgresqlSequenceIdGenerator idGenerator) {
    this.documentMapper = documentMapper;
    this.nodeMapper = nodeMapper;
    this.linkMapper = linkMapper;
    this.commandMapper = commandMapper;
    this.objectMapper = objectMapper;
    this.idGenerator = idGenerator;
  }

  @Override
  public Optional<CanvasSnapshot> findSnapshot(long canvasId) {
    CanvasDocumentDO document = documentMapper.getById(canvasId);
    if (document == null || !"ACTIVE".equals(document.getLifecycle())) {
      return Optional.empty();
    }
    return Optional.of(toSnapshot(document));
  }

  @Override
  public List<CanvasDocument> listDocuments(long workspaceId) {
    StudioWorkspaces.requireDefault(workspaceId);
    List<CanvasDocument> result = new ArrayList<>();
    for (CanvasDocumentDO document : documentMapper.listByWorkspace(workspaceId)) {
      result.add(toDocument(document));
    }
    return result;
  }

  @Override
  @Transactional
  public CanvasDocument createCanvas(long workspaceId, String title) {
    StudioWorkspaces.requireDefault(workspaceId);
    String normalized = title == null ? "" : title.trim();
    if (normalized.isEmpty()) {
      normalized = "未命名画布";
    }
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(idGenerator.next());
    document.setWorkspaceId(workspaceId);
    document.setTitle(normalized);
    document.setSchemaVersion(SCHEMA_VERSION);
    document.setRevision(0L);
    document.setLifecycle(CanvasLifecycle.ACTIVE.name());
    document.setHomeViewportJson(DEFAULT_VIEWPORT);
    documentMapper.insert(document);
    return toDocument(documentMapper.getById(document.getId()));
  }

  @Override
  @Transactional
  public CanvasDocument renameCanvas(long canvasId, long baseRevision, String title) {
    CanvasDocumentDO document = requireDocument(canvasId);
    if (!Objects.equals(document.getRevision(), baseRevision)) {
      throw new IllegalStateException("REVISION_CONFLICT");
    }
    String normalized = title == null ? "" : title.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("title must not be blank");
    }
    long nextRevision = baseRevision + 1;
    int updated =
        documentMapper.updateRevisionAndTitle(
            canvasId, baseRevision, nextRevision, normalized, document.getHomeViewportJson());
    if (updated != 1) {
      throw new IllegalStateException("REVISION_CONFLICT");
    }
    return toDocument(documentMapper.getById(canvasId));
  }

  @Override
  @Transactional
  public CanvasSnapshot applyCommands(
      long canvasId, long baseRevision, String commandId, String requestHash, String commandsJson) {
    if (commandId == null || commandId.isBlank()) {
      throw new IllegalArgumentException("commandId must not be blank");
    }
    if (requestHash == null || requestHash.isBlank()) {
      throw new IllegalArgumentException("requestHash must not be blank");
    }
    CanvasDocumentDO document = requireDocument(canvasId);
    CanvasCommandDO existing = commandMapper.getByCommandId(document.getWorkspaceId(), commandId);
    if (existing != null) {
      if (!requestHash.equals(existing.getRequestHash())) {
        throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
      }
      return findSnapshot(canvasId).orElseThrow();
    }
    if (!Objects.equals(document.getRevision(), baseRevision)) {
      throw new IllegalStateException("REVISION_CONFLICT");
    }

    JsonNode root;
    try {
      root = objectMapper.readTree(commandsJson == null ? "[]" : commandsJson);
    } catch (Exception ex) {
      throw new IllegalArgumentException("commandsJson must be valid JSON array", ex);
    }
    if (!root.isArray()) {
      throw new IllegalArgumentException("commandsJson must be a JSON array");
    }

    long nextRevision = baseRevision + 1;
    for (JsonNode command : root) {
      String type = text(command, "type");
      switch (type) {
        case "create_text_node" -> createTextNode(canvasId, nextRevision, command);
        case "create_generate_text_node" -> createGenerateTextNode(canvasId, nextRevision, command);
        case "create_link" -> createLink(canvasId, nextRevision, command);
        case "move_nodes" -> moveNodes(canvasId, nextRevision, command);
        case "delete_node" -> deleteNode(canvasId, nextRevision, command);
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
    CanvasCommandDO record = new CanvasCommandDO();
    record.setId(idGenerator.next());
    record.setCommandId(commandId);
    record.setWorkspaceId(document.getWorkspaceId());
    record.setCanvasId(canvasId);
    record.setBaseRevision(baseRevision);
    record.setResultRevision(nextRevision);
    record.setRequestHash(requestHash);
    record.setPayloadJson(commandsJson == null ? "[]" : commandsJson);
    record.setResultJson("{\"revision\":" + nextRevision + "}");
    commandMapper.insert(record);
    return snapshot;
  }

  private void createTextNode(long canvasId, long revision, JsonNode command) {
    double x = command.path("x").asDouble(120);
    double y = command.path("y").asDouble(120);
    String name = optionalText(command, "name", "文本");
    String body = optionalText(command, "text", "");
    CanvasNodeDO node = baseNode(canvasId, revision);
    node.setKind(CanvasNodeKind.RESOURCE.name());
    node.setNodeType("text");
    node.setName(name);
    node.setX(x);
    node.setY(y);
    node.setWidth(command.path("width").asDouble(240));
    node.setHeight(command.path("height").asDouble(120));
    node.setDataJson("{\"text\":" + quote(body) + "}");
    nodeMapper.insert(node);
  }

  private void createGenerateTextNode(long canvasId, long revision, JsonNode command) {
    double x = command.path("x").asDouble(420);
    double y = command.path("y").asDouble(120);
    String name = optionalText(command, "name", "文本生成");
    String prompt = optionalText(command, "prompt", "");
    CanvasNodeDO node = baseNode(canvasId, revision);
    node.setKind(CanvasNodeKind.FUNCTION.name());
    node.setNodeType(SystemFunctionIds.GENERATE_TEXT);
    node.setName(name);
    node.setX(x);
    node.setY(y);
    node.setWidth(command.path("width").asDouble(280));
    node.setHeight(command.path("height").asDouble(180));
    node.setDataJson(
        "{\"functionId\":\""
            + SystemFunctionIds.GENERATE_TEXT
            + "\",\"version\":\""
            + SystemFunctionIds.VERSION_V1
            + "\",\"prompt\":"
            + quote(prompt)
            + ",\"configRevision\":0}");
    nodeMapper.insert(node);
  }

  private void createLink(long canvasId, long revision, JsonNode command) {
    long source = command.path("sourceNodeId").asLong(0);
    long target = command.path("targetNodeId").asLong(0);
    if (source <= 0 || target <= 0 || source == target) {
      throw new IllegalArgumentException("create_link requires distinct sourceNodeId/targetNodeId");
    }
    if (nodeMapper.getActive(canvasId, source) == null
        || nodeMapper.getActive(canvasId, target) == null) {
      throw new IllegalArgumentException("create_link nodes must exist");
    }
    CanvasLinkDO link = new CanvasLinkDO();
    link.setId(idGenerator.next());
    link.setCanvasId(canvasId);
    link.setSourceNodeId(source);
    link.setTargetNodeId(target);
    link.setRevision(revision);
    linkMapper.insert(link);
  }

  private void moveNodes(long canvasId, long revision, JsonNode command) {
    JsonNode updates = command.path("updates");
    if (!updates.isArray()) {
      throw new IllegalArgumentException("move_nodes.updates must be an array");
    }
    for (JsonNode update : updates) {
      long id = update.path("id").asLong(0);
      CanvasNodeDO node = nodeMapper.getActive(canvasId, id);
      if (node == null) {
        throw new IllegalArgumentException("Unknown node: " + id);
      }
      node.setX(update.path("x").asDouble(node.getX()));
      node.setY(update.path("y").asDouble(node.getY()));
      node.setRevision(revision);
      nodeMapper.updatePosition(node);
    }
  }

  private void deleteNode(long canvasId, long revision, JsonNode command) {
    long id = command.path("id").asLong(0);
    if (nodeMapper.softDelete(canvasId, id, revision) != 1) {
      throw new IllegalArgumentException("Unknown node: " + id);
    }
  }

  private CanvasNodeDO baseNode(long canvasId, long revision) {
    CanvasNodeDO node = new CanvasNodeDO();
    node.setId(idGenerator.next());
    node.setCanvasId(canvasId);
    node.setNodeTypeVersion(1);
    node.setParentGroupId(null);
    node.setRotation(0d);
    node.setZIndex(1L);
    node.setLocked(false);
    node.setHidden(false);
    node.setValidity(toStorageValidity(NodeValidity.CURRENT));
    node.setRevision(revision);
    return node;
  }

  private static String toStorageValidity(NodeValidity validity) {
    return switch (validity) {
      case CURRENT, EMPTY -> "VALID";
      case STALE -> "STALE";
      case BROKEN -> "INVALID";
    };
  }

  private static NodeValidity fromStorageValidity(String stored) {
    if (stored == null) {
      return NodeValidity.EMPTY;
    }
    return switch (stored) {
      case "VALID" -> NodeValidity.CURRENT;
      case "INVALID" -> NodeValidity.BROKEN;
      case "STALE" -> NodeValidity.STALE;
      default -> throw new IllegalStateException("Unknown stored node validity: " + stored);
    };
  }

  private CanvasDocumentDO requireDocument(long canvasId) {
    CanvasDocumentDO document = documentMapper.getById(canvasId);
    if (document == null || !"ACTIVE".equals(document.getLifecycle())) {
      throw new IllegalArgumentException("Canvas not found: " + canvasId);
    }
    return document;
  }

  private CanvasSnapshot toSnapshot(CanvasDocumentDO document) {
    List<CanvasNode> nodes = new ArrayList<>();
    for (CanvasNodeDO node : nodeMapper.listActiveByCanvas(document.getId())) {
      nodes.add(toNode(node));
    }
    List<CanvasLink> links = new ArrayList<>();
    for (CanvasLinkDO link : linkMapper.listByCanvas(document.getId())) {
      links.add(
          new CanvasLink(
              link.getId(),
              link.getCanvasId(),
              link.getSourceNodeId(),
              link.getTargetNodeId(),
              link.getRevision()));
    }
    List<CanvasResourceReference> references = List.of();
    return new CanvasSnapshot(toDocument(document), nodes, links, references);
  }

  private static CanvasDocument toDocument(CanvasDocumentDO document) {
    return new CanvasDocument(
        document.getId(),
        document.getWorkspaceId(),
        document.getTitle(),
        document.getSchemaVersion(),
        document.getRevision(),
        CanvasLifecycle.valueOf(document.getLifecycle()),
        document.getHomeViewportJson());
  }

  private static CanvasNode toNode(CanvasNodeDO node) {
    return new CanvasNode(
        node.getId(),
        node.getCanvasId(),
        CanvasNodeKind.valueOf(node.getKind()),
        node.getNodeType(),
        node.getNodeTypeVersion(),
        node.getName(),
        node.getParentGroupId(),
        new NodeTransform(
            node.getX(), node.getY(), node.getWidth(), node.getHeight(), node.getRotation()),
        node.getZIndex(),
        Boolean.TRUE.equals(node.getLocked()),
        Boolean.TRUE.equals(node.getHidden()),
        fromStorageValidity(node.getValidity()),
        node.getRevision(),
        node.getDataJson());
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

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
