package fun.fengwk.kkstudio.core.studio;

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

import fun.fengwk.kkstudio.core.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionConfigCodec;
import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionModelRegistry;
import fun.fengwk.kkstudio.core.studio.realtime.CanvasRealtimeService;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasCommandDedupMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasGroupMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasCommandDedupDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasGroupDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasLinkDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasResourceDO;
import fun.fengwk.kkstudio.core.studio.resource.CanvasBlobPreviewService;
import fun.fengwk.kkstudio.core.studio.resource.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasLinkPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
public class DurableCanvasService implements CanvasCommandService {

  private static final String DEFAULT_TITLE = "未命名画布";
  private static final String COMMAND_HASH_ALGORITHM = "SHA-256";

  private final CanvasDocumentMapper documentMapper;
  private final CanvasGroupMapper groupMapper;
  private final CanvasNodeMapper nodeMapper;
  private final CanvasResourceMapper resourceMapper;
  private final CanvasLinkMapper linkMapper;
  private final CanvasCommandDedupMapper commandDedupMapper;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasResourceLifecycle resourceLifecycle;
  private final ObjectProvider<StorageUploadService> uploadServices;
  private final ObjectProvider<StorageBlobManager> blobManagers;
  private final ObjectProvider<CanvasBlobPreviewService> previewServices;
  private final CanvasFunctionConfigCodec functionConfigCodec;
  private final CanvasFunctionModelRegistry functionModelRegistry;
  private final ObjectMapper objectMapper;
  private final CanvasRealtimeService realtimeService;
  private final ObjectProvider<HarnessStore> harnessStores;
  private final ObjectProvider<SessionBlobRefManager> sessionBlobRefManagers;

  public DurableCanvasService(
      CanvasDocumentMapper documentMapper,
      CanvasGroupMapper groupMapper,
      CanvasNodeMapper nodeMapper,
      CanvasResourceMapper resourceMapper,
      CanvasLinkMapper linkMapper,
      CanvasCommandDedupMapper commandDedupMapper,
      CanvasFunctionRunRepository runRepository,
      CanvasResourceLifecycle resourceLifecycle,
      ObjectProvider<StorageUploadService> uploadServices,
      ObjectProvider<StorageBlobManager> blobManagers,
      ObjectProvider<CanvasBlobPreviewService> previewServices,
      CanvasFunctionConfigCodec functionConfigCodec,
      CanvasFunctionModelRegistry functionModelRegistry,
      ObjectMapper objectMapper,
      CanvasRealtimeService realtimeService,
      ObjectProvider<HarnessStore> harnessStores,
      ObjectProvider<SessionBlobRefManager> sessionBlobRefManagers) {
    this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
    this.groupMapper = Objects.requireNonNull(groupMapper, "groupMapper");
    this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
    this.resourceMapper = Objects.requireNonNull(resourceMapper, "resourceMapper");
    this.linkMapper = Objects.requireNonNull(linkMapper, "linkMapper");
    this.commandDedupMapper = Objects.requireNonNull(commandDedupMapper, "commandDedupMapper");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.uploadServices = Objects.requireNonNull(uploadServices, "uploadServices");
    this.blobManagers = Objects.requireNonNull(blobManagers, "blobManagers");
    this.previewServices = Objects.requireNonNull(previewServices, "previewServices");
    this.functionConfigCodec = Objects.requireNonNull(functionConfigCodec, "functionConfigCodec");
    this.functionModelRegistry =
        Objects.requireNonNull(functionModelRegistry, "functionModelRegistry");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService");
    this.harnessStores = Objects.requireNonNull(harnessStores, "harnessStores");
    this.sessionBlobRefManagers =
        Objects.requireNonNull(sessionBlobRefManagers, "sessionBlobRefManagers");
  }

  @Override
  @Transactional
  public CanvasDocument createCanvas(String title) {
    String canonicalTitle = canonicalDisplayName(title, DEFAULT_TITLE, "title");
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(UUID.randomUUID());
    document.setTitle(canonicalTitle);
    document.setVersion(0L);
    if (documentMapper.insert(document) != 1) {
      throw new IllegalStateException("insert canvas document failed");
    }
    CanvasDocumentDO persisted = documentMapper.getById(document.getId());
    if (persisted == null) {
      throw new IllegalStateException("canvas document disappeared after insert");
    }
    return toDocument(persisted);
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
    CanvasDocumentDO document = documentMapper.getByIdForUpdate(canvasId);
    if (document == null) {
      throw new IllegalArgumentException("Canvas not found: " + canvasId);
    }
    CanvasCommandDedupDO existing = commandDedupMapper.findById(canvasId, commandId);
    if (existing != null) {
      if (!requestHash.equals(existing.getRequestHash())) {
        throw conflict(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT);
      }
      // 精确回放：命令早已应用；返回当前版本的确定性空 patch（客户端按 version <= 本地版本忽略或按 base 对齐）。
      long currentVersion = document.getVersion();
      return new CanvasPatch(currentVersion, currentVersion, List.of(), List.of(), List.of());
    }
    if (document.getVersion() != expectedVersion) {
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
    if (documentMapper.compareAndSetVersion(canvasId, expectedVersion, newVersion) != 1) {
      throw conflict(CanvasConflictException.Reason.VERSION_CONFLICT);
    }
    CanvasCommandDedupDO dedup = new CanvasCommandDedupDO();
    dedup.setCanvasId(canvasId);
    dedup.setCommandId(commandId);
    dedup.setRequestHash(requestHash);
    dedup.setAppliedVersion(newVersion);
    try {
      commandDedupMapper.insert(dedup);
    } catch (DuplicateKeyException error) {
      throw conflict(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT);
    }
    CanvasPatch patch = accumulator.toPatch(expectedVersion, newVersion);
    realtimeService.publish(canvasId, patch);
    return patch;
  }

  @Override
  @Transactional
  public void deleteCanvas(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    CanvasDocumentDO document = documentMapper.getByIdForUpdate(canvasId);
    if (document == null) {
      throw new IllegalArgumentException("Canvas not found: " + canvasId);
    }
    resourceLifecycle.releaseCanvasPins(canvasId);
    resourceLifecycle.deleteCanvasResources(canvasId);
    runRepository.deleteByCanvasId(canvasId);
    linkMapper.deleteByCanvas(canvasId);
    for (CanvasNodeDO node : nodeMapper.listByCanvas(canvasId)) {
      nodeMapper.deleteById(canvasId, node.getId());
    }
    for (CanvasGroupDO group : groupMapper.listByCanvas(canvasId)) {
      groupMapper.deleteById(canvasId, group.getId());
    }
    commandDedupMapper.deleteByCanvas(canvasId);
    if (documentMapper.deleteById(canvasId) != 1) {
      throw new IllegalStateException("canvas document delete failed under row lock");
    }
    if (document.getThreadId() != null) {
      deepDeleteThread(document.getThreadId());
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
    if (nodeMapper.getById(canvasId, command.nodeId()) != null) {
      throw new IllegalArgumentException("node already exists: " + command.nodeId());
    }
    String name = canonicalNodeName(command.name());
    CanvasNodeDO node = newNode(canvasId, command.nodeId(), name, command.transform(), null, null);
    nodeMapper.insert(node);
    CanvasResourceDO resource = new CanvasResourceDO();
    resource.setId(UUID.randomUUID());
    resource.setCanvasId(canvasId);
    resource.setOwnerNodeId(node.getId());
    resource.setResourceIndex(0);
    resource.setBlobId(null);
    resource.setName(name);
    resource.setTextContent(command.markdown());
    resource.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    resourceMapper.insert(resource);
    accumulator.upsertNode(projectNode(node, List.of(resource), Map.of()));
  }

  private void updateTextNode(
      UUID canvasId, CanvasCommand.UpdateTextNode command, PatchAccumulator accumulator) {
    CanvasNodeDO node = requireNode(canvasId, command.nodeId());
    if (node.getModelKey() != null) {
      throw new IllegalArgumentException("UPDATE_TEXT_NODE requires an ordinary node");
    }
    if (resourceMapper.updateTextContent(canvasId, command.nodeId(), command.markdown()) != 1) {
      throw new IllegalArgumentException("UPDATE_TEXT_NODE requires one TEXT resource");
    }
    accumulator.upsertNode(projectNode(node, resourcesOfNode(canvasId, node.getId()), Map.of()));
  }

  private void createResourceNode(
      UUID canvasId, CanvasCommand.CreateResourceNode command, PatchAccumulator accumulator) {
    if (nodeMapper.getById(canvasId, command.nodeId()) != null) {
      throw new IllegalArgumentException("node already exists: " + command.nodeId());
    }
    requireDistinctIds(command.uploadIds(), "uploadIds");
    String name = canonicalNodeName(command.name());
    CanvasNodeDO node = newNode(canvasId, command.nodeId(), name, command.transform(), null, null);
    nodeMapper.insert(node);
    List<CanvasResourceDO> resources = new ArrayList<>(command.uploadIds().size());
    for (int index = 0; index < command.uploadIds().size(); index++) {
      resources.add(
          consumeUpload(canvasId, node.getId(), index, command.uploadIds().get(index), name));
    }
    for (CanvasResourceDO resource : resources) {
      resourceMapper.insert(resource);
      registerPreviewAfterCommit(resource);
    }
    accumulator.upsertNode(projectNode(node, resources, Map.of()));
  }

  private void createFunctionNode(
      UUID canvasId, CanvasCommand.CreateFunctionNode command, PatchAccumulator accumulator) {
    if (nodeMapper.getById(canvasId, command.nodeId()) != null) {
      throw new IllegalArgumentException("node already exists: " + command.nodeId());
    }
    String modelKey = canonicalModelKey(command.modelKey());
    String configJson = canonicalFunctionConfig(modelKey, command.configJson());
    String name = canonicalNodeName(command.name());
    CanvasNodeDO node =
        newNode(canvasId, command.nodeId(), name, command.transform(), modelKey, configJson);
    nodeMapper.insert(node);
    accumulator.upsertNode(projectNode(node, List.of(), Map.of()));
  }

  private void updateFunction(
      UUID canvasId, CanvasCommand.UpdateFunction command, PatchAccumulator accumulator) {
    String modelKey = canonicalModelKey(command.modelKey());
    String configJson = canonicalFunctionConfig(modelKey, command.configJson());
    CanvasNodeDO node = requireNode(canvasId, command.nodeId());
    if (node.getModelKey() == null) {
      throw new IllegalArgumentException("UPDATE_FUNCTION requires a Function node");
    }
    node.setModelKey(modelKey);
    node.setFunctionConfigJson(configJson);
    if (nodeMapper.updateFunction(node) != 1) {
      throw new IllegalArgumentException("Unknown Function node: " + command.nodeId());
    }
    accumulator.upsertNode(
        projectNode(
            node, resourcesOfNode(canvasId, node.getId()), runsOfNode(canvasId, node.getId())));
  }

  private void renameNode(
      UUID canvasId, CanvasCommand.RenameNode command, PatchAccumulator accumulator) {
    CanvasNodeDO node = requireNode(canvasId, command.nodeId());
    String name = canonicalNodeName(command.name());
    node.setName(name);
    if (nodeMapper.updateName(node) != 1) {
      throw new IllegalArgumentException("Unknown node: " + command.nodeId());
    }
    accumulator.upsertNode(
        projectNode(
            node, resourcesOfNode(canvasId, node.getId()), runsOfNode(canvasId, node.getId())));
  }

  private void updateNodeTransforms(
      UUID canvasId, CanvasCommand.UpdateNodeTransforms command, PatchAccumulator accumulator) {
    Set<UUID> seen = new HashSet<>();
    for (CanvasCommand.NodeTransformUpdate update : command.updates()) {
      if (!seen.add(update.nodeId())) {
        throw new IllegalArgumentException("updates must not contain duplicate node ids");
      }
      CanvasNodeDO node = requireNode(canvasId, update.nodeId());
      applyTransform(node, update.transform());
      if (nodeMapper.updateTransform(node) != 1) {
        throw new IllegalArgumentException("Unknown node: " + update.nodeId());
      }
      accumulator.upsertNode(
          projectNode(
              node, resourcesOfNode(canvasId, node.getId()), runsOfNode(canvasId, node.getId())));
    }
  }

  private void deleteNode(
      UUID canvasId, CanvasCommand.DeleteNode command, PatchAccumulator accumulator) {
    CanvasNodeDO node = requireNode(canvasId, command.nodeId());
    List<CanvasLinkDO> removedLinks =
        linkMapper.listByCanvas(canvasId).stream()
            .filter(
                link ->
                    link.getSourceNodeId().equals(node.getId())
                        || link.getTargetNodeId().equals(node.getId()))
            .toList();
    linkMapper.deleteByNode(canvasId, node.getId());
    for (CanvasLinkDO link : removedLinks) {
      accumulator.removeLink(link.getSourceNodeId(), link.getTargetNodeId());
    }
    resourceLifecycle.releaseNodePins(canvasId, node.getId());
    runRepository.deleteByNodeId(node.getId());
    resourceLifecycle.deleteOwnedResources(canvasId, node.getId());
    if (nodeMapper.deleteById(canvasId, node.getId()) != 1) {
      throw new IllegalArgumentException("Unknown node: " + command.nodeId());
    }
    accumulator.removeNode(node.getId());
  }

  private void createLink(
      UUID canvasId, CanvasCommand.CreateLink command, PatchAccumulator accumulator) {
    requireNode(canvasId, command.sourceNodeId());
    CanvasNodeDO target = requireNode(canvasId, command.targetNodeId());
    if (target.getModelKey() == null) {
      throw new IllegalArgumentException("link target must have a Function");
    }
    if (resourceMapper.listByOwnerNode(canvasId, command.sourceNodeId()).isEmpty()) {
      throw new IllegalArgumentException("link source must own at least one Resource");
    }
    if (linkMapper.exists(canvasId, command.sourceNodeId(), command.targetNodeId()) == 1) {
      throw new IllegalArgumentException("link already exists");
    }
    CanvasLinkDO link = new CanvasLinkDO();
    link.setCanvasId(canvasId);
    link.setSourceNodeId(command.sourceNodeId());
    link.setTargetNodeId(command.targetNodeId());
    linkMapper.insert(link);
    accumulator.upsertLink(
        new CanvasLink(canvasId, link.getSourceNodeId(), link.getTargetNodeId()));
  }

  private void deleteLink(
      UUID canvasId, CanvasCommand.DeleteLink command, PatchAccumulator accumulator) {
    requireNode(canvasId, command.sourceNodeId());
    requireNode(canvasId, command.targetNodeId());
    if (linkMapper.delete(canvasId, command.sourceNodeId(), command.targetNodeId()) != 1) {
      throw new IllegalArgumentException("Unknown link");
    }
    accumulator.removeLink(command.sourceNodeId(), command.targetNodeId());
  }

  private void createGroup(
      UUID canvasId, CanvasCommand.CreateGroup command, PatchAccumulator accumulator) {
    if (groupMapper.getById(canvasId, command.groupId()) != null) {
      throw new IllegalArgumentException("group already exists: " + command.groupId());
    }
    requireDistinctIds(command.memberNodeIds(), "memberNodeIds");
    CanvasGroupDO group = new CanvasGroupDO();
    group.setId(command.groupId());
    group.setCanvasId(canvasId);
    group.setTitle(canonicalDisplayName(command.title(), null, "title"));
    applyTransform(group, command.transform());
    groupMapper.insert(group);
    for (UUID memberNodeId : command.memberNodeIds()) {
      CanvasNodeDO node = requireNode(canvasId, memberNodeId);
      if (node.getGroupId() != null) {
        throw new IllegalArgumentException(
            "group member node already belongs to a group: " + memberNodeId);
      }
      node.setGroupId(group.getId());
      if (nodeMapper.attachGroupIfUngrouped(node) != 1) {
        throw new IllegalArgumentException("group member node is not ungrouped: " + memberNodeId);
      }
      accumulator.upsertNode(
          projectNode(
              node, resourcesOfNode(canvasId, node.getId()), runsOfNode(canvasId, node.getId())));
    }
    accumulator.upsertGroup(projectGroup(group));
  }

  private void moveGroup(
      UUID canvasId, CanvasCommand.MoveGroup command, PatchAccumulator accumulator) {
    CanvasGroupDO group = requireGroup(canvasId, command.groupId());
    double deltaX = command.x() - group.getX();
    double deltaY = command.y() - group.getY();
    if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY)) {
      throw new IllegalArgumentException("group move delta must be finite");
    }
    group.setX(command.x());
    group.setY(command.y());
    if (groupMapper.updatePosition(group) != 1) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
    nodeMapper.moveGroupMembers(canvasId, command.groupId(), deltaX, deltaY);
    for (CanvasNodeDO node : nodeMapper.listByCanvas(canvasId)) {
      if (Objects.equals(node.getGroupId(), command.groupId())) {
        accumulator.upsertNode(
            projectNode(
                node, resourcesOfNode(canvasId, node.getId()), runsOfNode(canvasId, node.getId())));
      }
    }
    accumulator.upsertGroup(projectGroup(group));
  }

  private void ungroup(UUID canvasId, CanvasCommand.Ungroup command, PatchAccumulator accumulator) {
    CanvasGroupDO group = requireGroup(canvasId, command.groupId());
    Set<UUID> current = new HashSet<>();
    for (CanvasNodeDO node : nodeMapper.listByCanvas(canvasId)) {
      if (Objects.equals(node.getGroupId(), command.groupId())) {
        current.add(node.getId());
      }
    }
    if (!current.equals(new HashSet<>(command.memberNodeIds()))) {
      throw new IllegalArgumentException("memberNodeIds must match the current group members");
    }
    nodeMapper.detachAllGroupMembers(canvasId, command.groupId());
    if (groupMapper.deleteById(canvasId, command.groupId()) != 1) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
    for (UUID memberNodeId : command.memberNodeIds()) {
      CanvasNodeDO node = requireNode(canvasId, memberNodeId);
      accumulator.upsertNode(
          projectNode(
              node, resourcesOfNode(canvasId, node.getId()), runsOfNode(canvasId, node.getId())));
    }
    accumulator.removeGroup(command.groupId());
  }

  private void deleteGroup(
      UUID canvasId, CanvasCommand.DeleteGroup command, PatchAccumulator accumulator) {
    CanvasGroupDO group = requireGroup(canvasId, command.groupId());
    List<CanvasNodeDO> members = new ArrayList<>();
    for (CanvasNodeDO node : nodeMapper.listByCanvas(canvasId)) {
      if (Objects.equals(node.getGroupId(), command.groupId())) {
        members.add(node);
      }
    }
    nodeMapper.detachAllGroupMembers(canvasId, command.groupId());
    if (groupMapper.deleteById(canvasId, command.groupId()) != 1) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
    for (CanvasNodeDO node : members) {
      accumulator.upsertNode(
          projectNode(
              node, resourcesOfNode(canvasId, node.getId()), runsOfNode(canvasId, node.getId())));
    }
    accumulator.removeGroup(command.groupId());
  }

  private void renameGroup(
      UUID canvasId, CanvasCommand.RenameGroup command, PatchAccumulator accumulator) {
    CanvasGroupDO group = requireGroup(canvasId, command.groupId());
    group.setTitle(canonicalDisplayName(command.title(), null, "title"));
    if (groupMapper.updateTitle(group) != 1) {
      throw new IllegalArgumentException("Unknown group: " + command.groupId());
    }
    accumulator.upsertGroup(projectGroup(group));
  }

  private CanvasResourceDO consumeUpload(
      UUID canvasId, UUID nodeId, int resourceIndex, UUID uploadId, String nodeName) {
    StorageUploadService uploadService = uploadServices.getIfAvailable();
    if (uploadService == null) {
      throw new IllegalStateException("global storage upload service is unavailable");
    }
    StorageUploadService.ReadyUpload upload = uploadService.lockReady(uploadId);
    UUID blobId = upload.blobId();
    StorageBlobManager blobManager = requireBlobManager();
    blobManager.retain(blobId);
    uploadService.delete(uploadId);
    String filename = upload.filename();
    CanvasResourceDO resource = new CanvasResourceDO();
    resource.setId(UUID.randomUUID());
    resource.setCanvasId(canvasId);
    resource.setOwnerNodeId(nodeId);
    resource.setResourceIndex(resourceIndex);
    resource.setBlobId(blobId);
    resource.setName(filename == null || filename.isBlank() ? nodeName : filename);
    resource.setTextContent(null);
    resource.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    return resource;
  }

  private void registerPreviewAfterCommit(CanvasResourceDO resource) {
    if (resource.getBlobId() == null) {
      return;
    }
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      return;
    }
    StorageBlob blob = blobManager.getBlob(resource.getBlobId());
    if (blob == null || !isPreviewable(blob.getMediaType())) {
      return;
    }
    CanvasBlobPreviewService previewService = previewServices.getIfAvailable();
    if (previewService == null) {
      return;
    }
    UUID blobId = resource.getBlobId();
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

  private void deepDeleteThread(UUID threadId) {
    HarnessStore harnessStore = harnessStores.getIfAvailable();
    if (harnessStore == null) {
      throw new IllegalStateException(
          "harness store is not available; cannot deep delete bound thread " + threadId);
    }
    harnessStore.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElse(null);
          if (thread == null) {
            throw new IllegalStateException("canvas bound thread " + threadId + " does not exist");
          }
          UUID sessionId = tx.loadEntryPath(thread.headEntryId()).root().sessionId();
          tx.deleteWorkByThread(threadId);
          tx.deleteToolInvocations(threadId);
          tx.deleteModelInvocations(threadId);
          tx.deleteCommands(threadId);
          tx.deleteThread(threadId);
          tx.deleteEntries(sessionId);
          SessionBlobRefManager refManager = sessionBlobRefManagers.getIfAvailable();
          if (refManager != null) {
            for (UUID blobId : refManager.listBlobIds(sessionId)) {
              refManager.releaseRef(sessionId, blobId);
            }
          }
          tx.deleteSession(sessionId);
          return null;
        });
  }

  private CanvasResourceNode projectNode(
      CanvasNodeDO node,
      List<CanvasResourceDO> resources,
      Map<UUID, CanvasFunctionRun> runsByNode) {
    List<CanvasResource> projected = new ArrayList<>(resources.size());
    for (CanvasResourceDO resource : resources) {
      projected.add(toResource(resource));
    }
    CanvasFunction function =
        node.getModelKey() == null
            ? null
            : new CanvasFunction(node.getModelKey(), node.getFunctionConfigJson());
    return new CanvasResourceNode(
        node.getId(),
        node.getCanvasId(),
        node.getName(),
        transformOf(node),
        node.getGroupId(),
        projected,
        function,
        runsByNode.get(node.getId()));
  }

  private List<CanvasResourceDO> resourcesOfNode(UUID canvasId, UUID nodeId) {
    return resourceMapper.listByOwnerNode(canvasId, nodeId);
  }

  private Map<UUID, CanvasFunctionRun> runsOfNode(UUID canvasId, UUID nodeId) {
    Map<UUID, CanvasFunctionRun> byNode = new HashMap<>();
    for (CanvasFunctionRun run : runRepository.findByCanvasId(canvasId)) {
      byNode.put(run.nodeId(), run);
    }
    return byNode;
  }

  private static CanvasGroup projectGroup(CanvasGroupDO group) {
    return new CanvasGroup(
        group.getId(),
        group.getCanvasId(),
        group.getTitle(),
        new CanvasTransform(group.getX(), group.getY(), group.getWidth(), group.getHeight()));
  }

  private String canonicalFunctionConfig(String modelKey, String configJson) {
    return functionConfigCodec.encode(
        functionConfigCodec.decode(configJson, functionModelRegistry.require(modelKey).model()));
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

  private CanvasNodeDO requireNode(UUID canvasId, UUID nodeId) {
    CanvasNodeDO node = nodeMapper.getById(canvasId, nodeId);
    if (node == null) {
      throw new IllegalArgumentException("Unknown node: " + nodeId);
    }
    return node;
  }

  private CanvasGroupDO requireGroup(UUID canvasId, UUID groupId) {
    CanvasGroupDO group = groupMapper.getById(canvasId, groupId);
    if (group == null) {
      throw new IllegalArgumentException("Unknown group: " + groupId);
    }
    return group;
  }

  private static CanvasNodeDO newNode(
      UUID canvasId,
      UUID nodeId,
      String name,
      CanvasTransform transform,
      String modelKey,
      String functionConfigJson) {
    CanvasNodeDO node = new CanvasNodeDO();
    node.setId(nodeId);
    node.setCanvasId(canvasId);
    node.setName(name);
    applyTransform(node, transform);
    node.setModelKey(modelKey);
    node.setFunctionConfigJson(functionConfigJson);
    return node;
  }

  private static CanvasDocument toDocument(CanvasDocumentDO document) {
    return new CanvasDocument(
        document.getId(),
        document.getTitle(),
        document.getVersion(),
        document.getThreadId(),
        document.getCreatedAt().toInstant(),
        document.getUpdatedAt().toInstant());
  }

  private static CanvasResource toResource(CanvasResourceDO resource) {
    return new CanvasResource(
        resource.getId(),
        resource.getCanvasId(),
        resource.getOwnerNodeId(),
        resource.getResourceIndex(),
        resource.getBlobId(),
        resource.getName(),
        resource.getTextContent(),
        resource.getCreatedAt().toInstant());
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
