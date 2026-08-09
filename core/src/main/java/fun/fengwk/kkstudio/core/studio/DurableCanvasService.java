package fun.fengwk.kkstudio.core.studio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.studio.repo.impl.PostgresqlCanvasResourceRepository;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasCommandDedupMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasGroupMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasCommandDedupDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasGroupDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasLinkDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeResourceDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasResourceDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL-backed Canvas v1 command/query service. */
public class DurableCanvasService implements CanvasQueryService, CanvasCommandService {

  private static final String DEFAULT_TITLE = "未命名画布";
  private static final String MARKDOWN_MEDIA_TYPE = "text/markdown";
  private static final String EMPTY_METADATA_JSON = "{}";

  private final CanvasDocumentMapper documentMapper;
  private final CanvasGroupMapper groupMapper;
  private final CanvasNodeMapper nodeMapper;
  private final CanvasResourceMapper resourceMapper;
  private final CanvasNodeResourceMapper nodeResourceMapper;
  private final CanvasLinkMapper linkMapper;
  private final CanvasCommandDedupMapper commandDedupMapper;
  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionRunRepository functionRunRepository;
  private final ObjectMapper objectMapper;
  private final PostgresqlSequenceIdGenerator idGenerator;

  public DurableCanvasService(
      CanvasDocumentMapper documentMapper,
      CanvasGroupMapper groupMapper,
      CanvasNodeMapper nodeMapper,
      CanvasResourceMapper resourceMapper,
      CanvasNodeResourceMapper nodeResourceMapper,
      CanvasLinkMapper linkMapper,
      CanvasCommandDedupMapper commandDedupMapper,
      CanvasResourceRepository resourceRepository,
      CanvasFunctionRunRepository functionRunRepository,
      ObjectMapper objectMapper,
      PostgresqlSequenceIdGenerator idGenerator) {
    this.documentMapper = documentMapper;
    this.groupMapper = groupMapper;
    this.nodeMapper = nodeMapper;
    this.resourceMapper = resourceMapper;
    this.nodeResourceMapper = nodeResourceMapper;
    this.linkMapper = linkMapper;
    this.commandDedupMapper = commandDedupMapper;
    this.resourceRepository = resourceRepository;
    this.functionRunRepository = functionRunRepository;
    this.objectMapper = objectMapper;
    this.idGenerator = idGenerator;
  }

  @Override
  public Optional<CanvasSnapshot> findSnapshot(long canvasId) {
    if (canvasId <= 0L) {
      return Optional.empty();
    }
    CanvasDocumentDO document = documentMapper.getById(canvasId);
    return document == null ? Optional.empty() : Optional.of(toSnapshot(document));
  }

  @Override
  public List<CanvasDocument> listDocuments() {
    List<CanvasDocument> documents = new ArrayList<>();
    for (CanvasDocumentDO document : documentMapper.listAll()) {
      documents.add(toDocument(document));
    }
    return List.copyOf(documents);
  }

  @Override
  @Transactional
  public CanvasDocument createCanvas(String title) {
    String canonicalTitle = canonicalDisplayName(title, DEFAULT_TITLE, "title");
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(idGenerator.next());
    document.setTitle(canonicalTitle);
    document.setGraphRevision(0L);
    documentMapper.insert(document);
    return toDocument(documentMapper.getById(document.getId()));
  }

  @Override
  @Transactional
  public CanvasSnapshot applyCommands(
      long canvasId, long expectedRevision, String commandId, List<CanvasCommand> commands) {
    if (canvasId <= 0L) {
      throw new IllegalArgumentException("canvasId must be > 0");
    }
    if (expectedRevision < 0L) {
      throw new IllegalArgumentException("expectedRevision must be >= 0");
    }
    if (commandId == null || commandId.isBlank() || commandId.length() > 128) {
      throw new IllegalArgumentException("commandId must be non-blank and at most 128 characters");
    }
    Objects.requireNonNull(commands, "commands");
    List<CanvasCommand> commandBatch = List.copyOf(commands);
    if (commandBatch.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }

    String requestHash = requestHash(commandBatch);
    CanvasDocumentDO document = requireDocument(canvasId);
    CanvasCommandDedupDO existing = commandDedupMapper.findById(canvasId, commandId);
    if (existing != null) {
      if (!requestHash.equals(existing.getRequestHash())) {
        throw conflict(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT);
      }
      return findSnapshot(canvasId).orElseThrow();
    }
    if (document.getGraphRevision() != expectedRevision) {
      throw conflict(CanvasConflictException.Reason.REVISION_CONFLICT);
    }

    try {
      for (CanvasCommand command : commandBatch) {
        execute(canvasId, command);
      }
    } catch (DuplicateKeyException ex) {
      throw new IllegalArgumentException("duplicate Canvas graph value", ex);
    }

    long appliedRevision;
    try {
      appliedRevision = Math.addExact(expectedRevision, 1L);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("expectedRevision is too large", ex);
    }
    if (documentMapper.compareAndSetRevision(canvasId, expectedRevision, appliedRevision) != 1) {
      throw conflict(CanvasConflictException.Reason.REVISION_CONFLICT);
    }

    CanvasCommandDedupDO dedup = new CanvasCommandDedupDO();
    dedup.setCanvasId(canvasId);
    dedup.setCommandId(commandId);
    dedup.setRequestHash(requestHash);
    dedup.setAppliedRevision(appliedRevision);
    commandDedupMapper.insert(dedup);
    return findSnapshot(canvasId).orElseThrow();
  }

  private void execute(long canvasId, CanvasCommand command) {
    switch (command) {
      case CanvasCommand.CreateTextNode value -> createTextNode(canvasId, value);
      case CanvasCommand.UpdateTextNode value -> updateTextNode(canvasId, value);
      case CanvasCommand.CreateResourceNode value -> createResourceNode(canvasId, value);
      case CanvasCommand.CreateFunctionNode value -> createFunctionNode(canvasId, value);
      case CanvasCommand.UpdateFunction value -> updateFunction(canvasId, value);
      case CanvasCommand.RenameNode value -> renameNode(canvasId, value);
      case CanvasCommand.UpdateNodeTransforms value -> updateNodeTransforms(canvasId, value);
      case CanvasCommand.DeleteNode value -> deleteNode(canvasId, value);
      case CanvasCommand.CreateLink value -> createLink(canvasId, value);
      case CanvasCommand.DeleteLink value -> deleteLink(canvasId, value);
      case CanvasCommand.CreateGroup value -> createGroup(canvasId, value);
      case CanvasCommand.MoveGroup value -> moveGroup(canvasId, value);
      case CanvasCommand.Ungroup value -> ungroup(canvasId, value);
      case CanvasCommand.DeleteGroup value -> deleteGroup(canvasId, value);
    }
  }

  private void createTextNode(long canvasId, CanvasCommand.CreateTextNode command) {
    String name = canonicalNodeName(command.name());
    long nodeId = idGenerator.next();
    CanvasResource resource =
        new CanvasResource(
            idGenerator.next(),
            canvasId,
            CanvasResourceKind.TEXT,
            MARKDOWN_MEDIA_TYPE,
            name,
            command.markdown().getBytes(StandardCharsets.UTF_8).length,
            command.markdown(),
            EMPTY_METADATA_JSON,
            Instant.now());
    resourceRepository.add(resource);
    nodeMapper.insert(newNode(nodeId, canvasId, name, command.transform(), null, null));
    attachResources(canvasId, nodeId, List.of(resource.id()));
  }

  private void updateTextNode(long canvasId, CanvasCommand.UpdateTextNode command) {
    CanvasNodeDO node = requireNode(canvasId, command.nodeId());
    if (node.getModelKey() != null) {
      throw new IllegalArgumentException("UPDATE_TEXT_NODE requires an ordinary node");
    }
    List<CanvasResourceDO> current = resourceMapper.listByNode(canvasId, command.nodeId());
    if (current.size() != 1
        || CanvasResourceKind.valueOf(current.get(0).getKind()) != CanvasResourceKind.TEXT) {
      throw new IllegalArgumentException("UPDATE_TEXT_NODE requires one TEXT resource");
    }
    CanvasResourceDO previous = current.get(0);
    CanvasResource replacement =
        new CanvasResource(
            idGenerator.next(),
            canvasId,
            CanvasResourceKind.TEXT,
            previous.getMediaType(),
            previous.getName(),
            command.markdown().getBytes(StandardCharsets.UTF_8).length,
            command.markdown(),
            previous.getMetadataJson(),
            Instant.now());
    resourceRepository.add(replacement);
    nodeResourceMapper.deleteByNode(canvasId, command.nodeId());
    attachResources(canvasId, command.nodeId(), List.of(replacement.id()));
  }

  private void createResourceNode(long canvasId, CanvasCommand.CreateResourceNode command) {
    requireDistinctIds(command.resourceIds(), "resourceIds");
    List<CanvasResource> resources = resourceRepository.findByIds(canvasId, command.resourceIds());
    if (resources.size() != command.resourceIds().size()) {
      throw new IllegalArgumentException("all resources must exist in the same canvas");
    }
    CanvasResourceKind kind = resources.get(0).kind();
    if (resources.stream().anyMatch(resource -> resource.kind() != kind)) {
      throw new IllegalArgumentException("resourceIds must have the same kind");
    }
    String name = canonicalNodeName(command.name());
    long nodeId = idGenerator.next();
    nodeMapper.insert(newNode(nodeId, canvasId, name, command.transform(), null, null));
    attachResources(canvasId, nodeId, command.resourceIds());
  }

  private void createFunctionNode(long canvasId, CanvasCommand.CreateFunctionNode command) {
    validateJsonObject(command.configJson(), "configJson");
    String name = canonicalNodeName(command.name());
    nodeMapper.insert(
        newNode(
            idGenerator.next(),
            canvasId,
            name,
            command.transform(),
            canonicalModelKey(command.modelKey()),
            command.configJson()));
  }

  private void updateFunction(long canvasId, CanvasCommand.UpdateFunction command) {
    validateJsonObject(command.configJson(), "configJson");
    CanvasNodeDO node = requireNode(canvasId, command.nodeId());
    if (node.getModelKey() == null) {
      throw new IllegalArgumentException("UPDATE_FUNCTION requires a Function node");
    }
    node.setModelKey(canonicalModelKey(command.modelKey()));
    node.setFunctionConfigJson(command.configJson());
    if (nodeMapper.updateFunction(node) != 1) {
      throw new IllegalArgumentException("Unknown Function node: " + command.nodeId());
    }
  }

  private void renameNode(long canvasId, CanvasCommand.RenameNode command) {
    CanvasNodeDO node = requireNode(canvasId, command.nodeId());
    String name = canonicalNodeName(command.name());
    node.setName(name);
    node.setNameNormalized(normalizedNodeName(name));
    if (nodeMapper.updateName(node) != 1) {
      throw new IllegalArgumentException("Unknown node: " + command.nodeId());
    }
  }

  private void updateNodeTransforms(long canvasId, CanvasCommand.UpdateNodeTransforms command) {
    Set<Long> seen = new HashSet<>();
    for (CanvasCommand.NodeTransformUpdate update : command.updates()) {
      if (!seen.add(update.nodeId())) {
        throw new IllegalArgumentException("updates must not contain duplicate node ids");
      }
      CanvasNodeDO node = requireNode(canvasId, update.nodeId());
      applyTransform(node, update.transform());
      if (nodeMapper.updateTransform(node) != 1) {
        throw new IllegalArgumentException("Unknown node: " + update.nodeId());
      }
    }
  }

  private void deleteNode(long canvasId, CanvasCommand.DeleteNode command) {
    if (nodeMapper.deleteById(canvasId, command.nodeId()) != 1) {
      throw new IllegalArgumentException("Unknown node: " + command.nodeId());
    }
  }

  private void createLink(long canvasId, CanvasCommand.CreateLink command) {
    requireNode(canvasId, command.sourceNodeId());
    CanvasNodeDO target = requireNode(canvasId, command.targetNodeId());
    if (target.getModelKey() == null) {
      throw new IllegalArgumentException("link target must have a Function");
    }
    CanvasLinkDO link = new CanvasLinkDO();
    link.setCanvasId(canvasId);
    link.setSourceNodeId(command.sourceNodeId());
    link.setTargetNodeId(command.targetNodeId());
    linkMapper.insert(link);
  }

  private void deleteLink(long canvasId, CanvasCommand.DeleteLink command) {
    if (linkMapper.delete(canvasId, command.sourceNodeId(), command.targetNodeId()) != 1) {
      throw new IllegalArgumentException("Unknown link");
    }
  }

  private void createGroup(long canvasId, CanvasCommand.CreateGroup command) {
    requireDistinctIds(command.memberNodeIds(), "memberNodeIds");
    CanvasGroupDO group = new CanvasGroupDO();
    group.setId(idGenerator.next());
    group.setCanvasId(canvasId);
    group.setTitle(canonicalDisplayName(command.title(), null, "title"));
    applyTransform(group, command.transform());
    groupMapper.insert(group);
    for (Long nodeId : command.memberNodeIds()) {
      CanvasNodeDO node = requireNode(canvasId, nodeId);
      if (node.getGroupId() != null) {
        throw new IllegalArgumentException(
            "group member node already belongs to a group: " + nodeId);
      }
      node.setGroupId(group.getId());
      if (nodeMapper.attachGroupIfUngrouped(node) != 1) {
        throw new IllegalArgumentException("group member node is not ungrouped: " + nodeId);
      }
    }
  }

  private void moveGroup(long canvasId, CanvasCommand.MoveGroup command) {
    CanvasGroupDO group = requireGroup(canvasId, command.groupId());
    double deltaX = command.x() - group.getX();
    double deltaY = command.y() - group.getY();
    if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY)) {
      throw new IllegalArgumentException("group move delta must be finite");
    }
    for (CanvasNodeDO node : nodeMapper.listByCanvas(canvasId)) {
      if (Objects.equals(node.getGroupId(), command.groupId())) {
        new CanvasTransform(
            node.getX() + deltaX, node.getY() + deltaY, node.getWidth(), node.getHeight());
      }
    }
    group.setX(command.x());
    group.setY(command.y());
    if (groupMapper.updatePosition(group) != 1) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
    nodeMapper.moveGroupMembers(canvasId, command.groupId(), deltaX, deltaY);
  }

  private void ungroup(long canvasId, CanvasCommand.Ungroup command) {
    requireGroup(canvasId, command.groupId());
    requireDistinctIds(command.memberNodeIds(), "memberNodeIds");
    for (Long nodeId : command.memberNodeIds()) {
      if (nodeMapper.detachGroupMember(canvasId, command.groupId(), nodeId) != 1) {
        throw new IllegalArgumentException("node is not a member of the group: " + nodeId);
      }
    }
  }

  private void deleteGroup(long canvasId, CanvasCommand.DeleteGroup command) {
    requireGroup(canvasId, command.groupId());
    nodeMapper.detachAllGroupMembers(canvasId, command.groupId());
    if (groupMapper.deleteById(canvasId, command.groupId()) != 1) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
  }

  private CanvasSnapshot toSnapshot(CanvasDocumentDO document) {
    long canvasId = document.getId();
    Map<Long, List<CanvasResource>> resourcesByNode = new LinkedHashMap<>();
    for (CanvasResourceDO resource : resourceMapper.listByCanvasNodeOrder(canvasId)) {
      resourcesByNode
          .computeIfAbsent(resource.getNodeId(), ignored -> new ArrayList<>())
          .add(PostgresqlCanvasResourceRepository.toDomain(resource));
    }
    Map<Long, CanvasFunctionRun> runsByNode = new HashMap<>();
    for (CanvasFunctionRun run : functionRunRepository.findByCanvasId(canvasId)) {
      runsByNode.put(run.nodeId(), run);
    }

    List<CanvasResourceNode> nodes = new ArrayList<>();
    for (CanvasNodeDO node : nodeMapper.listByCanvas(canvasId)) {
      CanvasFunction function =
          node.getModelKey() == null
              ? null
              : new CanvasFunction(node.getModelKey(), node.getFunctionConfigJson());
      nodes.add(
          new CanvasResourceNode(
              node.getId(),
              node.getCanvasId(),
              node.getName(),
              transformOf(node),
              node.getGroupId(),
              resourcesByNode.getOrDefault(node.getId(), List.of()),
              function,
              runsByNode.get(node.getId())));
    }

    List<CanvasGroup> groups = new ArrayList<>();
    for (CanvasGroupDO group : groupMapper.listByCanvas(canvasId)) {
      groups.add(
          new CanvasGroup(
              group.getId(),
              group.getCanvasId(),
              group.getTitle(),
              new CanvasTransform(
                  group.getX(), group.getY(), group.getWidth(), group.getHeight())));
    }

    List<CanvasLink> links = new ArrayList<>();
    for (CanvasLinkDO link : linkMapper.listByCanvas(canvasId)) {
      links.add(new CanvasLink(link.getCanvasId(), link.getSourceNodeId(), link.getTargetNodeId()));
    }
    return new CanvasSnapshot(toDocument(document), nodes, groups, links);
  }

  private CanvasDocumentDO requireDocument(long canvasId) {
    CanvasDocumentDO document = documentMapper.getById(canvasId);
    if (document == null) {
      throw new IllegalArgumentException("Canvas not found: " + canvasId);
    }
    return document;
  }

  private CanvasNodeDO requireNode(long canvasId, long nodeId) {
    CanvasNodeDO node = nodeMapper.getById(canvasId, nodeId);
    if (node == null) {
      throw new IllegalArgumentException("Unknown node: " + nodeId);
    }
    return node;
  }

  private CanvasGroupDO requireGroup(long canvasId, long groupId) {
    CanvasGroupDO group = groupMapper.getById(canvasId, groupId);
    if (group == null) {
      throw new IllegalArgumentException("Unknown group: " + groupId);
    }
    return group;
  }

  private CanvasNodeDO newNode(
      long nodeId,
      long canvasId,
      String name,
      CanvasTransform transform,
      String modelKey,
      String functionConfigJson) {
    CanvasNodeDO node = new CanvasNodeDO();
    node.setId(nodeId);
    node.setCanvasId(canvasId);
    node.setName(name);
    node.setNameNormalized(normalizedNodeName(name));
    applyTransform(node, transform);
    node.setModelKey(modelKey);
    node.setFunctionConfigJson(functionConfigJson);
    return node;
  }

  private void attachResources(long canvasId, long nodeId, List<Long> resourceIds) {
    for (int index = 0; index < resourceIds.size(); index++) {
      CanvasNodeResourceDO relation = new CanvasNodeResourceDO();
      relation.setCanvasId(canvasId);
      relation.setNodeId(nodeId);
      relation.setResourceIndex(index);
      relation.setResourceId(resourceIds.get(index));
      nodeResourceMapper.insert(relation);
    }
  }

  private String requestHash(List<CanvasCommand> commands) {
    ArrayNode canonical = objectMapper.createArrayNode();
    for (CanvasCommand command : commands) {
      ObjectNode item = objectMapper.createObjectNode();
      item.put("type", commandType(command));
      item.set("payload", objectMapper.valueToTree(command));
      canonical.add(item);
    }
    try {
      return sha256Hex(objectMapper.writeValueAsBytes(canonical));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("command hash serialization failed", ex);
    }
  }

  private void validateJsonObject(String json, String field) {
    try {
      JsonNode node = objectMapper.readTree(json);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException(field + " must be a JSON object");
      }
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(field + " must be valid JSON", ex);
    }
  }

  private static String commandType(CanvasCommand command) {
    return switch (command) {
      case CanvasCommand.CreateTextNode ignored -> "CREATE_TEXT_NODE";
      case CanvasCommand.UpdateTextNode ignored -> "UPDATE_TEXT_NODE";
      case CanvasCommand.CreateResourceNode ignored -> "CREATE_RESOURCE_NODE";
      case CanvasCommand.CreateFunctionNode ignored -> "CREATE_FUNCTION_NODE";
      case CanvasCommand.UpdateFunction ignored -> "UPDATE_FUNCTION";
      case CanvasCommand.RenameNode ignored -> "RENAME_NODE";
      case CanvasCommand.UpdateNodeTransforms ignored -> "UPDATE_NODE_TRANSFORMS";
      case CanvasCommand.DeleteNode ignored -> "DELETE_NODE";
      case CanvasCommand.CreateLink ignored -> "CREATE_LINK";
      case CanvasCommand.DeleteLink ignored -> "DELETE_LINK";
      case CanvasCommand.CreateGroup ignored -> "CREATE_GROUP";
      case CanvasCommand.MoveGroup ignored -> "MOVE_GROUP";
      case CanvasCommand.Ungroup ignored -> "UNGROUP";
      case CanvasCommand.DeleteGroup ignored -> "DELETE_GROUP";
    };
  }

  private static CanvasConflictException conflict(CanvasConflictException.Reason reason) {
    return new CanvasConflictException(reason);
  }

  private static CanvasDocument toDocument(CanvasDocumentDO document) {
    return new CanvasDocument(
        document.getId(),
        document.getTitle(),
        document.getGraphRevision(),
        document.getCreatedAt().toInstant(),
        document.getUpdatedAt().toInstant());
  }

  private static CanvasTransform transformOf(CanvasNodeDO node) {
    return new CanvasTransform(node.getX(), node.getY(), node.getWidth(), node.getHeight());
  }

  private static void applyTransform(CanvasNodeDO node, CanvasTransform transform) {
    node.setX(transform.x());
    node.setY(transform.y());
    node.setWidth(transform.width());
    node.setHeight(transform.height());
  }

  private static void applyTransform(CanvasGroupDO group, CanvasTransform transform) {
    group.setX(transform.x());
    group.setY(transform.y());
    group.setWidth(transform.width());
    group.setHeight(transform.height());
  }

  private static String canonicalNodeName(String name) {
    return canonicalDisplayName(name, null, "name");
  }

  private static String canonicalModelKey(String modelKey) {
    String canonical = modelKey.strip();
    if (canonical.length() > 256) {
      throw new IllegalArgumentException("modelKey must be at most 256 characters");
    }
    return canonical;
  }

  private static String canonicalDisplayName(String value, String defaultValue, String field) {
    String candidate =
        value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
    if (candidate.isEmpty() && defaultValue != null) {
      candidate = defaultValue;
    }
    if (candidate.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (candidate.length() > 256) {
      throw new IllegalArgumentException(field + " must be at most 256 characters");
    }
    return candidate;
  }

  private static String normalizedNodeName(String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    if (normalized.length() > 256) {
      throw new IllegalArgumentException("normalized name must be at most 256 characters");
    }
    return normalized;
  }

  private static void requireDistinctIds(List<Long> ids, String field) {
    if (new HashSet<>(ids).size() != ids.size()) {
      throw new IllegalArgumentException(field + " must not contain duplicates");
    }
  }

  private static String sha256Hex(byte[] input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
