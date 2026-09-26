package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandHash;
import fun.fengwk.kkstudio.canvas.CanvasCommandPlan;
import fun.fengwk.kkstudio.canvas.CanvasCommandPlanner;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult.Accepted;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult.Conflicted;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasGraph;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasMutation;
import fun.fengwk.kkstudio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.CommandDedup;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasValidationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * typed command 与画布生命周期的 PostgreSQL 事务实现。
 *
 * <p>所有写入都在 {@code canvas_document} 行锁内的短事务中完成，锁内不做上传、模型调用或任何其他外部 I/O。因此同一画布的写入按 document
 * 行锁串行提交：幂等判定、语义组前置条件校验、行级落库与 revision 前进要么整体成功，要么整体回滚。冲突不写入任何行，也不返回新基线。
 */
@Repository
public class PostgresqlCanvasCommandService implements CanvasCommandService {

  /** 与 {@code canvas_document.title} 列宽一致的标题上限。 */
  private static final int MAX_TITLE_LENGTH = 256;

  private final CanvasStore canvasStore;
  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionResourcePinRepository pinRepository;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasResourceLifecycle resourceLifecycle;
  private final CanvasCommandPlanner planner;

  public PostgresqlCanvasCommandService(
      CanvasStore canvasStore,
      CanvasResourceRepository resourceRepository,
      CanvasFunctionResourcePinRepository pinRepository,
      CanvasFunctionRunRepository runRepository,
      CanvasResourceLifecycle resourceLifecycle,
      CanvasCommandPlanner planner) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.pinRepository = Objects.requireNonNull(pinRepository, "pinRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.planner = Objects.requireNonNull(planner, "planner");
  }

  @Override
  @Transactional
  public CanvasDocument createCanvas(String title) {
    if (title == null) {
      throw new CanvasValidationException("title must not be null");
    }
    String validated = title.strip();
    if (validated.isEmpty()) {
      throw new CanvasValidationException("title must not be blank");
    }
    if (validated.length() > MAX_TITLE_LENGTH) {
      throw new CanvasValidationException(
          "title must not exceed " + MAX_TITLE_LENGTH + " characters");
    }
    for (int index = 0; index < validated.length(); index++) {
      if (Character.isISOControl(validated.charAt(index))) {
        throw new CanvasValidationException("title must not contain control characters");
      }
    }
    return canvasStore.addDocument(UUID.randomUUID(), validated);
  }

  @Override
  @Transactional
  public CanvasCommandResult applyCommands(
      UUID canvasId, UUID idempotencyKey, List<CanvasCommand> commands) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(commands, "commands");
    String requestHash = CanvasCommandHash.of(commands);
    CanvasDocument document =
        canvasStore
            .lockDocument(canvasId)
            .orElseThrow(
                () ->
                    new CanvasConflictException(
                        CanvasConflictException.Reason.CANVAS_NOT_FOUND,
                        "canvas document not found: " + canvasId));
    CommandDedup dedup = canvasStore.findCommandDedup(canvasId, idempotencyKey).orElse(null);
    if (dedup != null) {
      if (!dedup.requestHash().equals(requestHash)) {
        throw new CanvasConflictException(
            CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT,
            "idempotency key is bound to a different request");
      }
      return new Accepted(CanvasPatch.receipt(dedup.acceptedRevision()));
    }
    CanvasCommandPlan plan = planner.plan(loadGraph(canvasId), commands);
    if (plan.isRejected()) {
      return new Conflicted(plan.conflicts());
    }
    long acceptedRevision = document.revision();
    if (plan.hasChanges()) {
      applyMutations(canvasId, plan.mutations());
      acceptedRevision++;
      if (!canvasStore.advanceRevision(canvasId, document.revision(), acceptedRevision)) {
        throw new IllegalStateException("canvas revision CAS failed under document row lock");
      }
    }
    canvasStore.addCommandDedup(
        new CommandDedup(canvasId, idempotencyKey, requestHash, acceptedRevision));
    return new Accepted(plan.toPatch(acceptedRevision));
  }

  @Override
  @Transactional
  public void deleteCanvas(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    if (canvasStore.lockDocument(canvasId).isEmpty()) {
      return;
    }
    for (CanvasFunctionRun run : runRepository.findByCanvasId(canvasId)) {
      if (run.status() == CanvasFunctionRunStatus.READY
          || run.status() == CanvasFunctionRunStatus.RUNNING) {
        throw new IllegalStateException("canvas has a non-terminal Function run: " + run.nodeId());
      }
    }
    resourceLifecycle.releaseCanvasPins(canvasId);
    runRepository.deleteByCanvasId(canvasId);
    resourceLifecycle.deleteCanvasResources(canvasId);
    for (NodeRecord node : canvasStore.listNodes(canvasId)) {
      if (!canvasStore.deleteNode(canvasId, node.id())) {
        throw new IllegalStateException("delete canvas node failed: " + node.id());
      }
    }
    for (CanvasGroup group : canvasStore.listGroups(canvasId)) {
      if (!canvasStore.deleteGroup(canvasId, group.id())) {
        throw new IllegalStateException("delete canvas group failed: " + group.id());
      }
    }
    canvasStore.deleteCommandDedupByCanvas(canvasId);
    if (!canvasStore.deleteDocument(canvasId)) {
      throw new IllegalStateException("delete canvas document failed: " + canvasId);
    }
  }

  /** 读取 document 行锁内的权威快照，作为命令前沿的判定基础。 */
  private CanvasGraph loadGraph(UUID canvasId) {
    Map<UUID, List<CanvasResource>> owned = new HashMap<>();
    for (CanvasResource resource : resourceRepository.findByCanvasId(canvasId)) {
      if (resource.ownerNodeId() == null) {
        continue;
      }
      owned.computeIfAbsent(resource.ownerNodeId(), ignored -> new ArrayList<>()).add(resource);
    }
    Map<UUID, CanvasFunctionRun> runs = new HashMap<>();
    for (CanvasFunctionRun run : runRepository.findByCanvasId(canvasId)) {
      runs.put(run.nodeId(), run);
    }
    List<CanvasResourceNode> nodes = new ArrayList<>();
    for (NodeRecord node : canvasStore.listNodes(canvasId)) {
      List<CanvasResource> resources = new ArrayList<>(owned.getOrDefault(node.id(), List.of()));
      resources.sort(Comparator.comparingInt(CanvasResource::resourceIndex));
      nodes.add(
          new CanvasResourceNode(
              node.id(),
              node.canvasId(),
              node.name(),
              node.transform(),
              node.groupId(),
              resources,
              node.function(),
              runs.get(node.id())));
    }
    Set<UUID> pinnedResourceIds = new LinkedHashSet<>();
    for (CanvasFunctionResourcePin pin : pinRepository.findByCanvas(canvasId)) {
      pinnedResourceIds.add(pin.resourceId());
    }
    return new CanvasGraph(canvasId, nodes, canvasStore.listGroups(canvasId), pinnedResourceIds);
  }

  /** 按规划顺序执行行级步骤；任何一步不满足都抛出，使整个事务回滚。 */
  private void applyMutations(UUID canvasId, List<CanvasMutation> mutations) {
    for (CanvasMutation mutation : mutations) {
      switch (mutation) {
        case CanvasMutation.InsertNode insert -> canvasStore.addNode(toRecord(canvasId, insert));
        case CanvasMutation.UpdateNode update -> {
          if (!canvasStore.updateNode(toRecord(canvasId, update))) {
            throw new IllegalStateException("update canvas node failed: " + update.nodeId());
          }
        }
        case CanvasMutation.DeleteNode delete -> {
          if (!canvasStore.deleteNode(canvasId, delete.nodeId())) {
            throw new IllegalStateException("delete canvas node failed: " + delete.nodeId());
          }
        }
        case CanvasMutation.InsertResource insert -> resourceRepository.add(insert.resource());
        case CanvasMutation.AttachResource attach -> {
          if (!resourceRepository.attachOwner(
              canvasId, attach.resourceId(), attach.nodeId(), attach.resourceIndex())) {
            throw new IllegalStateException(
                "attach canvas resource failed: " + attach.resourceId());
          }
        }
        case CanvasMutation.DetachResource detach -> {
          if (!resourceRepository.detachOwner(canvasId, detach.resourceId(), detach.nodeId())) {
            throw new IllegalStateException(
                "detach canvas resource failed: " + detach.resourceId());
          }
        }
        case CanvasMutation.DeleteResource delete -> resourceLifecycle.discardResource(
            canvasId, delete.resourceId());
        case CanvasMutation.InsertGroup insert -> canvasStore.addGroup(insert.group());
        case CanvasMutation.UpdateGroup update -> {
          if (!canvasStore.updateGroup(update.group())) {
            throw new IllegalStateException("update canvas group failed: " + update.group().id());
          }
        }
        case CanvasMutation.DeleteGroup delete -> {
          if (!canvasStore.deleteGroup(canvasId, delete.groupId())) {
            throw new IllegalStateException("delete canvas group failed: " + delete.groupId());
          }
        }
        case CanvasMutation.ReleaseNodePins release -> resourceLifecycle.releaseNodePins(
            canvasId, release.nodeId());
        case CanvasMutation.DeleteFunctionRun delete -> {
          if (!runRepository.deleteByNodeId(delete.nodeId())) {
            throw new IllegalStateException(
                "delete canvas function run failed: " + delete.nodeId());
          }
        }
      }
    }
  }

  private static NodeRecord toRecord(UUID canvasId, CanvasMutation.InsertNode insert) {
    return new NodeRecord(
        insert.nodeId(),
        canvasId,
        insert.name(),
        insert.transform(),
        insert.groupId(),
        insert.function());
  }

  private static NodeRecord toRecord(UUID canvasId, CanvasMutation.UpdateNode update) {
    return new NodeRecord(
        update.nodeId(),
        canvasId,
        update.name(),
        update.transform(),
        update.groupId(),
        update.function());
  }
}
