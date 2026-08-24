package fun.fengwk.kkstudio.platform.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.canvas.CanvasLink;
import fun.fengwk.kkstudio.canvas.CanvasLinkPatch;
import fun.fengwk.kkstudio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.CommandDedup;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfigCodecPort;
import fun.fengwk.kkstudio.platform.canvas.resource.CanvasBlobPreviewService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL-backed Canvas v1 command service。
 *
 * <p>事务内先锁定 document 行串行化全部命令，再按 {@code (canvasId, commandId)} 幂等：相同 commandId + request hash
 * 精确回放（返回当前版本的确定性空 patch），相同 commandId 不同 hash 冲突；{@code expectedVersion} 在应用后 CAS 校验。
 * CREATE_RESOURCE_NODE 在同一事务内消费 READY 全局上传（行锁 → retain blob → 删上传行 → 释放上传引用）， 资源 blob 引用只经
 * StorageBlobManager 转移；删除节点/画布同步释放全部 owned blob。
 */
@Slf4j
@Service
public class PlatformCanvasCommandService implements CanvasCommandService {

  private static final String DEFAULT_TITLE = "未命名画布";
  private static final String COMMAND_HASH_ALGORITHM = "SHA-256";

  private final CanvasStore canvasStore;
  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasResourceLifecycle resourceLifecycle;
  private final ObjectProvider<StorageUploadService> uploadServices;
  private final ObjectProvider<StorageBlobManager> blobManagers;
  private final ObjectProvider<CanvasBlobPreviewService> previewServices;
  private final CanvasFunctionConfigCodecPort functionConfigCodec;
  private final CanvasFunctionCatalog functionCatalog;
  private final ObjectMapper objectMapper;
  private final SessionDeletionOrchestrator sessionDeletionService;

  public PlatformCanvasCommandService(
      CanvasStore canvasStore,
      CanvasResourceRepository resourceRepository,
      CanvasFunctionRunRepository runRepository,
      CanvasResourceLifecycle resourceLifecycle,
      ObjectProvider<StorageUploadService> uploadServices,
      ObjectProvider<StorageBlobManager> blobManagers,
      ObjectProvider<CanvasBlobPreviewService> previewServices,
      CanvasFunctionConfigCodecPort functionConfigCodec,
      CanvasFunctionCatalog functionCatalog,
      ObjectMapper objectMapper,
      SessionDeletionOrchestrator sessionDeletionService) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.uploadServices = Objects.requireNonNull(uploadServices, "uploadServices");
    this.blobManagers = Objects.requireNonNull(blobManagers, "blobManagers");
    this.previewServices = Objects.requireNonNull(previewServices, "previewServices");
    this.functionConfigCodec = Objects.requireNonNull(functionConfigCodec, "functionConfigCodec");
    this.functionCatalog = Objects.requireNonNull(functionCatalog, "functionCatalog");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.sessionDeletionService =
        Objects.requireNonNull(sessionDeletionService, "sessionDeletionService");
  }

  @Override
  @Transactional
  public CanvasDocument createCanvas(String title) {
    String canonicalTitle = canonicalDisplayName(title, DEFAULT_TITLE, "title");
    return canvasStore.addDocument(UUID.randomUUID(), canonicalTitle);
  }

  @Override
  @Transactional
  public CanvasPatch applyCommands(
      UUID canvasId, long expectedVersion, UUID commandId, List<CanvasCommand> commands) {
    Objects.requireNonNull(canvasId, "canvasId");
    if (expectedVersion < 0L) {
      throw new IllegalArgumentException("expectedVersion must be >= 0");
    }
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(commands, "commands");
    List<CanvasCommand> commandBatch = List.copyOf(commands);
    if (commandBatch.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }

    String requestHash = requestHash(commandBatch);
    // 行锁先于一切：并发命令与精确回放都在 document 行锁上串行化。
    CanvasDocument document =
        canvasStore
            .lockDocument(canvasId)
            .orElseThrow(() -> new IllegalArgumentException("Canvas not found: " + canvasId));
    CommandDedup existing = canvasStore.findCommandDedup(canvasId, commandId).orElse(null);
    if (existing != null) {
      if (!requestHash.equals(existing.requestHash())) {
        throw conflict(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT);
      }
      // 精确回放：命令早已应用；返回当前版本的确定性空 patch（客户端按 version <= 本地版本忽略或按 base 对齐）。
      long currentVersion = document.version();
      return new CanvasPatch(currentVersion, currentVersion, List.of(), List.of(), List.of());
    }
    if (document.version() != expectedVersion) {
      throw conflict(CanvasConflictException.Reason.VERSION_CONFLICT);
    }

    PatchAccumulator accumulator = new PatchAccumulator();
    try {
      for (CanvasCommand command : commandBatch) {
        execute(canvasId, command, accumulator);
      }
    } catch (DuplicateKeyException error) {
      throw new IllegalArgumentException("duplicate Canvas graph value", error);
    }

    long newVersion = Math.addExact(expectedVersion, 1L);
    if (!canvasStore.advanceDocumentVersion(canvasId, expectedVersion, newVersion)) {
      throw conflict(CanvasConflictException.Reason.VERSION_CONFLICT);
    }
    try {
      canvasStore.addCommandDedup(new CommandDedup(canvasId, commandId, requestHash));
    } catch (DuplicateKeyException error) {
      throw conflict(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT);
    }
    return accumulator.toPatch(expectedVersion, newVersion);
  }

  @Override
  @Transactional
  public void deleteCanvas(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    canvasStore
        .lockDocument(canvasId)
        .orElseThrow(() -> new IllegalArgumentException("Canvas not found: " + canvasId));
    // canvas_document 行锁（Owner FOR UPDATE）保护：先删 graph 内容，再经共享会话深删除移除全部归属 Session
    // （relation 行先于 document 行删除，FK RESTRICT 顺序由应用显式驱动），最后 CAS 删除 document 行。
    resourceLifecycle.releaseCanvasPins(canvasId);
    resourceLifecycle.deleteCanvasResources(canvasId);
    runRepository.deleteByCanvasId(canvasId);
    canvasStore.deleteLinksByCanvas(canvasId);
    for (NodeRecord node : canvasStore.listNodes(canvasId)) {
      canvasStore.deleteNode(canvasId, node.id());
    }
    for (CanvasGroup group : canvasStore.listGroups(canvasId)) {
      canvasStore.deleteGroup(canvasId, group.id());
    }
    canvasStore.deleteCommandDedupByCanvas(canvasId);
    sessionDeletionService.deleteSessionsByOwner(new OwnerRef(OwnerType.CANVAS, canvasId));
    if (!canvasStore.deleteDocument(canvasId)) {
      throw new IllegalStateException("canvas document delete failed under row lock");
    }
  }

  private void execute(UUID canvasId, CanvasCommand command, PatchAccumulator accumulator) {
    switch (command) {
      case CanvasCommand.CreateTextNode value -> createTextNode(canvasId, value, accumulator);
      case CanvasCommand.UpdateTextNode value -> updateTextNode(canvasId, value, accumulator);
      case CanvasCommand.CreateResourceNode value -> createResourceNode(
          canvasId, value, accumulator);
      case CanvasCommand.CreateFunctionNode value -> createFunctionNode(
          canvasId, value, accumulator);
      case CanvasCommand.UpdateFunction value -> updateFunction(canvasId, value, accumulator);
      case CanvasCommand.RenameNode value -> renameNode(canvasId, value, accumulator);
      case CanvasCommand.UpdateNodeTransforms value -> updateNodeTransforms(
          canvasId, value, accumulator);
      case CanvasCommand.DeleteNode value -> deleteNode(canvasId, value, accumulator);
      case CanvasCommand.CreateLink value -> createLink(canvasId, value, accumulator);
      case CanvasCommand.DeleteLink value -> deleteLink(canvasId, value, accumulator);
      case CanvasCommand.CreateGroup value -> createGroup(canvasId, value, accumulator);
      case CanvasCommand.MoveGroup value -> moveGroup(canvasId, value, accumulator);
      case CanvasCommand.Ungroup value -> ungroup(canvasId, value, accumulator);
      case CanvasCommand.DeleteGroup value -> deleteGroup(canvasId, value, accumulator);
      case CanvasCommand.RenameGroup value -> renameGroup(canvasId, value, accumulator);
    }
  }

  private void createTextNode(
      UUID canvasId, CanvasCommand.CreateTextNode command, PatchAccumulator accumulator) {
    if (canvasStore.findNode(canvasId, command.nodeId()).isPresent()) {
      throw new IllegalArgumentException("node already exists: " + command.nodeId());
    }
    String name = canonicalNodeName(command.name());
    NodeRecord node = newNode(canvasId, command.nodeId(), name, command.transform(), null, null);
    canvasStore.addNode(node);
    CanvasResource resource =
        new CanvasResource(
            UUID.randomUUID(),
            canvasId,
            node.id(),
            0,
            null,
            name,
            command.markdown(),
            Instant.now());
    resourceRepository.add(resource);
    accumulator.upsertNode(projectNode(node, List.of(resource), Map.of()));
  }

  private void updateTextNode(
      UUID canvasId, CanvasCommand.UpdateTextNode command, PatchAccumulator accumulator) {
    NodeRecord node = requireNode(canvasId, command.nodeId());
    if (node.modelKey() != null) {
      throw new IllegalArgumentException("UPDATE_TEXT_NODE requires an ordinary node");
    }
    if (!resourceRepository.updateTextContent(canvasId, command.nodeId(), command.markdown())) {
      throw new IllegalArgumentException("UPDATE_TEXT_NODE requires one TEXT resource");
    }
    accumulator.upsertNode(projectNode(node, resourcesOfNode(canvasId, node.id()), Map.of()));
  }

  private void createResourceNode(
      UUID canvasId, CanvasCommand.CreateResourceNode command, PatchAccumulator accumulator) {
    if (canvasStore.findNode(canvasId, command.nodeId()).isPresent()) {
      throw new IllegalArgumentException("node already exists: " + command.nodeId());
    }
    requireDistinctIds(command.uploadIds(), "uploadIds");
    String name = canonicalNodeName(command.name());
    NodeRecord node = newNode(canvasId, command.nodeId(), name, command.transform(), null, null);
    canvasStore.addNode(node);
    List<CanvasResource> resources = new ArrayList<>(command.uploadIds().size());
    for (int index = 0; index < command.uploadIds().size(); index++) {
      resources.add(
          consumeUpload(canvasId, node.id(), index, command.uploadIds().get(index), name));
    }
    for (CanvasResource resource : resources) {
      resourceRepository.add(resource);
      registerPreviewAfterCommit(resource);
    }
    accumulator.upsertNode(projectNode(node, resources, Map.of()));
  }

  private void createFunctionNode(
      UUID canvasId, CanvasCommand.CreateFunctionNode command, PatchAccumulator accumulator) {
    if (canvasStore.findNode(canvasId, command.nodeId()).isPresent()) {
      throw new IllegalArgumentException("node already exists: " + command.nodeId());
    }
    String modelKey = canonicalModelKey(command.modelKey());
    String configJson = canonicalFunctionConfig(modelKey, command.configJson());
    String name = canonicalNodeName(command.name());
    NodeRecord node =
        newNode(canvasId, command.nodeId(), name, command.transform(), modelKey, configJson);
    canvasStore.addNode(node);
    accumulator.upsertNode(projectNode(node, List.of(), Map.of()));
  }

  private void updateFunction(
      UUID canvasId, CanvasCommand.UpdateFunction command, PatchAccumulator accumulator) {
    String modelKey = canonicalModelKey(command.modelKey());
    String configJson = canonicalFunctionConfig(modelKey, command.configJson());
    NodeRecord node = requireNode(canvasId, command.nodeId());
    if (node.modelKey() == null) {
      throw new IllegalArgumentException("UPDATE_FUNCTION requires a Function node");
    }
    node = node.withFunction(modelKey, configJson);
    if (!canvasStore.updateNodeFunction(canvasId, node.id(), modelKey, configJson)) {
      throw new IllegalArgumentException("Unknown Function node: " + command.nodeId());
    }
    accumulator.upsertNode(
        projectNode(node, resourcesOfNode(canvasId, node.id()), runsOfNode(canvasId, node.id())));
  }

  private void renameNode(
      UUID canvasId, CanvasCommand.RenameNode command, PatchAccumulator accumulator) {
    NodeRecord node = requireNode(canvasId, command.nodeId());
    String name = canonicalNodeName(command.name());
    node = node.withName(name);
    if (!canvasStore.renameNode(canvasId, node.id(), name)) {
      throw new IllegalArgumentException("Unknown node: " + command.nodeId());
    }
    accumulator.upsertNode(
        projectNode(node, resourcesOfNode(canvasId, node.id()), runsOfNode(canvasId, node.id())));
  }

  private void updateNodeTransforms(
      UUID canvasId, CanvasCommand.UpdateNodeTransforms command, PatchAccumulator accumulator) {
    Set<UUID> seen = new HashSet<>();
    for (CanvasCommand.NodeTransformUpdate update : command.updates()) {
      if (!seen.add(update.nodeId())) {
        throw new IllegalArgumentException("updates must not contain duplicate node ids");
      }
      NodeRecord node = requireNode(canvasId, update.nodeId()).withTransform(update.transform());
      if (!canvasStore.updateNodeTransform(node)) {
        throw new IllegalArgumentException("Unknown node: " + update.nodeId());
      }
      accumulator.upsertNode(
          projectNode(node, resourcesOfNode(canvasId, node.id()), runsOfNode(canvasId, node.id())));
    }
  }

  private void deleteNode(
      UUID canvasId, CanvasCommand.DeleteNode command, PatchAccumulator accumulator) {
    NodeRecord node = requireNode(canvasId, command.nodeId());
    List<CanvasLink> removedLinks =
        canvasStore.listLinks(canvasId).stream()
            .filter(
                link ->
                    link.sourceNodeId().equals(node.id()) || link.targetNodeId().equals(node.id()))
            .toList();
    canvasStore.deleteLinksByNode(canvasId, node.id());
    for (CanvasLink link : removedLinks) {
      accumulator.removeLink(link.sourceNodeId(), link.targetNodeId());
    }
    resourceLifecycle.releaseNodePins(canvasId, node.id());
    runRepository.deleteByNodeId(node.id());
    resourceLifecycle.deleteOwnedResources(canvasId, node.id());
    if (!canvasStore.deleteNode(canvasId, node.id())) {
      throw new IllegalArgumentException("Unknown node: " + command.nodeId());
    }
    accumulator.removeNode(node.id());
  }

  private void createLink(
      UUID canvasId, CanvasCommand.CreateLink command, PatchAccumulator accumulator) {
    requireNode(canvasId, command.sourceNodeId());
    NodeRecord target = requireNode(canvasId, command.targetNodeId());
    if (target.modelKey() == null) {
      throw new IllegalArgumentException("link target must have a Function");
    }
    if (resourceRepository.findByOwnerNode(canvasId, command.sourceNodeId()).isEmpty()) {
      throw new IllegalArgumentException("link source must own at least one Resource");
    }
    if (canvasStore.linkExists(canvasId, command.sourceNodeId(), command.targetNodeId())) {
      throw new IllegalArgumentException("link already exists");
    }
    CanvasLink link = new CanvasLink(canvasId, command.sourceNodeId(), command.targetNodeId());
    canvasStore.addLink(link);
    accumulator.upsertLink(link);
  }

  private void deleteLink(
      UUID canvasId, CanvasCommand.DeleteLink command, PatchAccumulator accumulator) {
    requireNode(canvasId, command.sourceNodeId());
    requireNode(canvasId, command.targetNodeId());
    if (!canvasStore.deleteLink(canvasId, command.sourceNodeId(), command.targetNodeId())) {
      throw new IllegalArgumentException("Unknown link");
    }
    accumulator.removeLink(command.sourceNodeId(), command.targetNodeId());
  }

  private void createGroup(
      UUID canvasId, CanvasCommand.CreateGroup command, PatchAccumulator accumulator) {
    if (canvasStore.findGroup(canvasId, command.groupId()).isPresent()) {
      throw new IllegalArgumentException("group already exists: " + command.groupId());
    }
    requireDistinctIds(command.memberNodeIds(), "memberNodeIds");
    CanvasGroup group =
        new CanvasGroup(
            command.groupId(),
            canvasId,
            canonicalDisplayName(command.title(), null, "title"),
            command.transform());
    canvasStore.addGroup(group);
    for (UUID memberNodeId : command.memberNodeIds()) {
      NodeRecord node = requireNode(canvasId, memberNodeId);
      if (node.groupId() != null) {
        throw new IllegalArgumentException(
            "group member node already belongs to a group: " + memberNodeId);
      }
      node = node.withGroupId(group.id());
      if (!canvasStore.attachNodeToGroupIfUngrouped(canvasId, memberNodeId, group.id())) {
        throw new IllegalArgumentException("group member node is not ungrouped: " + memberNodeId);
      }
      accumulator.upsertNode(
          projectNode(node, resourcesOfNode(canvasId, node.id()), runsOfNode(canvasId, node.id())));
    }
    accumulator.upsertGroup(group);
  }

  private void moveGroup(
      UUID canvasId, CanvasCommand.MoveGroup command, PatchAccumulator accumulator) {
    CanvasGroup group = requireGroup(canvasId, command.groupId());
    double deltaX = command.x() - group.transform().x();
    double deltaY = command.y() - group.transform().y();
    if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY)) {
      throw new IllegalArgumentException("group move delta must be finite");
    }
    group =
        new CanvasGroup(
            group.id(),
            group.canvasId(),
            group.title(),
            new CanvasTransform(
                command.x(), command.y(), group.transform().width(), group.transform().height()));
    if (!canvasStore.moveGroup(group)) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
    canvasStore.moveGroupNodes(canvasId, command.groupId(), deltaX, deltaY);
    for (NodeRecord node : canvasStore.listNodes(canvasId)) {
      if (Objects.equals(node.groupId(), command.groupId())) {
        accumulator.upsertNode(
            projectNode(
                node, resourcesOfNode(canvasId, node.id()), runsOfNode(canvasId, node.id())));
      }
    }
    accumulator.upsertGroup(group);
  }

  private void ungroup(UUID canvasId, CanvasCommand.Ungroup command, PatchAccumulator accumulator) {
    CanvasGroup group = requireGroup(canvasId, command.groupId());
    Map<UUID, NodeRecord> current = new HashMap<>();
    for (NodeRecord node : canvasStore.listNodes(canvasId)) {
      if (Objects.equals(node.groupId(), command.groupId())) {
        current.put(node.id(), node);
      }
    }
    Set<UUID> requested = new HashSet<>(command.memberNodeIds());
    if (requested.size() != command.memberNodeIds().size()) {
      throw new IllegalArgumentException("memberNodeIds must not contain duplicates");
    }
    if (!current.keySet().containsAll(requested)) {
      throw new IllegalArgumentException("memberNodeIds must belong to the current group");
    }
    for (UUID memberNodeId : command.memberNodeIds()) {
      if (!canvasStore.detachNodeFromGroup(canvasId, command.groupId(), memberNodeId)) {
        throw new IllegalArgumentException("Unknown group member: " + memberNodeId);
      }
    }
    for (UUID memberNodeId : command.memberNodeIds()) {
      NodeRecord node = current.get(memberNodeId).withGroupId(null);
      accumulator.upsertNode(
          projectNode(node, resourcesOfNode(canvasId, node.id()), runsOfNode(canvasId, node.id())));
    }
    if (requested.size() == current.size()) {
      if (!canvasStore.deleteGroup(canvasId, command.groupId())) {
        throw new IllegalArgumentException("Unknown group: " + command.groupId());
      }
      accumulator.removeGroup(command.groupId());
    }
  }

  private void deleteGroup(
      UUID canvasId, CanvasCommand.DeleteGroup command, PatchAccumulator accumulator) {
    requireGroup(canvasId, command.groupId());
    List<NodeRecord> members = new ArrayList<>();
    for (NodeRecord node : canvasStore.listNodes(canvasId)) {
      if (Objects.equals(node.groupId(), command.groupId())) {
        members.add(node);
      }
    }
    canvasStore.detachAllNodesFromGroup(canvasId, command.groupId());
    if (!canvasStore.deleteGroup(canvasId, command.groupId())) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
    for (NodeRecord member : members) {
      NodeRecord node = member.withGroupId(null);
      accumulator.upsertNode(
          projectNode(node, resourcesOfNode(canvasId, node.id()), runsOfNode(canvasId, node.id())));
    }
    accumulator.removeGroup(command.groupId());
  }

  private void renameGroup(
      UUID canvasId, CanvasCommand.RenameGroup command, PatchAccumulator accumulator) {
    CanvasGroup current = requireGroup(canvasId, command.groupId());
    CanvasGroup group =
        new CanvasGroup(
            current.id(),
            current.canvasId(),
            canonicalDisplayName(command.title(), null, "title"),
            current.transform());
    if (!canvasStore.renameGroup(canvasId, group.id(), group.title())) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
    accumulator.upsertGroup(group);
  }

  private CanvasResource consumeUpload(
      UUID canvasId, UUID nodeId, int resourceIndex, UUID uploadId, String nodeName) {
    StorageUploadService uploadService = uploadServices.getIfAvailable();
    if (uploadService == null) {
      throw new IllegalStateException("global storage upload service is unavailable");
    }
    StorageUploadService.ReadyUpload upload = uploadService.lockReady(uploadId);
    UUID blobId = upload.blobId();
    StorageBlobManager blobManager = requireBlobManager();
    blobManager.retain(blobId);
    // delete 在当前事务内只标记 cleanup request 并 release upload owner；对象清理由提交后的 Maintenance 完成。
    uploadService.delete(uploadId);
    String filename = upload.filename();
    return new CanvasResource(
        UUID.randomUUID(),
        canvasId,
        nodeId,
        resourceIndex,
        blobId,
        filename == null || filename.isBlank() ? nodeName : filename,
        null,
        Instant.now());
  }

  private void registerPreviewAfterCommit(CanvasResource resource) {
    if (resource.blobId() == null) {
      return;
    }
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      return;
    }
    StorageBlob blob = blobManager.getBlob(resource.blobId());
    if (blob == null || !isPreviewable(blob.getMediaType())) {
      return;
    }
    CanvasBlobPreviewService previewService = previewServices.getIfAvailable();
    if (previewService == null) {
      return;
    }
    UUID blobId = resource.blobId();
    String mediaType = blob.getMediaType();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              previewService.ensurePreview(blobId, mediaType);
            } catch (RuntimeException error) {
              log.warn(
                  "blob preview generation failed after upload consumption blobId={} type={}",
                  blobId,
                  error.getClass().getSimpleName());
            }
          }
        });
  }

  private static boolean isPreviewable(String mediaType) {
    return mediaType != null && (mediaType.startsWith("image/") || mediaType.startsWith("video/"));
  }

  private StorageBlobManager requireBlobManager() {
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      throw new IllegalStateException("global blob storage is unavailable");
    }
    return blobManager;
  }

  private CanvasResourceNode projectNode(
      NodeRecord node, List<CanvasResource> resources, Map<UUID, CanvasFunctionRun> runsByNode) {
    CanvasFunction function =
        node.modelKey() == null
            ? null
            : new CanvasFunction(node.modelKey(), node.functionConfigJson());
    return new CanvasResourceNode(
        node.id(),
        node.canvasId(),
        node.name(),
        node.transform(),
        node.groupId(),
        resources,
        function,
        runsByNode.get(node.id()));
  }

  private List<CanvasResource> resourcesOfNode(UUID canvasId, UUID nodeId) {
    return resourceRepository.findByOwnerNode(canvasId, nodeId);
  }

  private Map<UUID, CanvasFunctionRun> runsOfNode(UUID canvasId, UUID nodeId) {
    Map<UUID, CanvasFunctionRun> byNode = new HashMap<>();
    for (CanvasFunctionRun run : runRepository.findByCanvasId(canvasId)) {
      byNode.put(run.nodeId(), run);
    }
    return byNode;
  }

  private String canonicalFunctionConfig(String modelKey, String configJson) {
    return functionConfigCodec.encode(
        functionConfigCodec.decode(configJson, functionCatalog.require(modelKey).model()));
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
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("command hash serialization failed", error);
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
      case CanvasCommand.RenameGroup ignored -> "RENAME_GROUP";
    };
  }

  private static CanvasConflictException conflict(CanvasConflictException.Reason reason) {
    return new CanvasConflictException(reason);
  }

  private NodeRecord requireNode(UUID canvasId, UUID nodeId) {
    return canvasStore
        .findNode(canvasId, nodeId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown node: " + nodeId));
  }

  private CanvasGroup requireGroup(UUID canvasId, UUID groupId) {
    return canvasStore
        .findGroup(canvasId, groupId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown group: " + groupId));
  }

  private static NodeRecord newNode(
      UUID canvasId,
      UUID nodeId,
      String name,
      CanvasTransform transform,
      String modelKey,
      String functionConfigJson) {
    return new NodeRecord(nodeId, canvasId, name, transform, null, modelKey, functionConfigJson);
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
    if (candidate == null || candidate.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (candidate.length() > 256) {
      throw new IllegalArgumentException(field + " must be at most 256 characters");
    }
    return candidate;
  }

  private static void requireDistinctIds(List<UUID> ids, String field) {
    if (new HashSet<>(ids).size() != ids.size()) {
      throw new IllegalArgumentException(field + " must not contain duplicates");
    }
  }

  private static String sha256Hex(byte[] input) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance(COMMAND_HASH_ALGORITHM).digest(input));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(COMMAND_HASH_ALGORITHM + " not available", error);
    }
  }

  /** 命令批的 patch 累积：按实体 id 去重，同 id 后写覆盖先写，最终顺序为首次出现顺序。 */
  private static final class PatchAccumulator {

    private final Map<UUID, CanvasGroupPatch> groups = new LinkedHashMap<>();
    private final Map<UUID, CanvasNodePatch> nodes = new LinkedHashMap<>();
    private final Map<CanvasLinkKey, CanvasLinkPatch> links = new LinkedHashMap<>();

    void upsertGroup(CanvasGroup group) {
      groups.put(group.id(), new CanvasGroupPatch.Upsert(group));
    }

    void removeGroup(UUID groupId) {
      groups.put(groupId, new CanvasGroupPatch.Remove(groupId));
    }

    void upsertNode(CanvasResourceNode node) {
      nodes.put(node.id(), new CanvasNodePatch.Upsert(node));
    }

    void removeNode(UUID nodeId) {
      nodes.put(nodeId, new CanvasNodePatch.Remove(nodeId));
    }

    void upsertLink(CanvasLink link) {
      links.put(
          new CanvasLinkKey(link.sourceNodeId(), link.targetNodeId()),
          new CanvasLinkPatch.Upsert(link));
    }

    void removeLink(UUID sourceNodeId, UUID targetNodeId) {
      links.put(
          new CanvasLinkKey(sourceNodeId, targetNodeId),
          new CanvasLinkPatch.Remove(sourceNodeId, targetNodeId));
    }

    CanvasPatch toPatch(long baseVersion, long version) {
      return new CanvasPatch(
          baseVersion,
          version,
          List.copyOf(groups.values()),
          List.copyOf(nodes.values()),
          List.copyOf(links.values()));
    }

    private record CanvasLinkKey(UUID sourceNodeId, UUID targetNodeId) {}
  }
}
