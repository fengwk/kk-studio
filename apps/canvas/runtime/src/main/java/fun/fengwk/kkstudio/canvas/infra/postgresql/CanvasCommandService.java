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
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasGraph;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasMutation;
import fun.fengwk.kkstudio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CommandDedup;
import fun.fengwk.kkstudio.canvas.NodeRecord;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 画布生命周期与 typed command 的 PostgreSQL 事务实现。
 *
 * <p>所有写入都在 {@code canvas_document} 行锁内的短事务中完成，锁内不做上传、模型调用或任何其他外部 I/O。因此同一画布的写入按 document
 * 行锁串行提交：幂等判定、语义组前置条件校验、行级落库与 revision 前进要么整体成功，要么整体回滚。冲突不写入任何行，也不返回新基线。
 */
@Repository
public class CanvasCommandService {

  /** 与 {@code canvas_document.title} 列宽一致的标题上限。 */
  private static final int MAX_TITLE_LENGTH = 256;

  private final PostgresqlCanvasStore canvasStore;
  private final PostgresqlCanvasResourceRepository resourceRepository;
  private final PostgresqlCanvasFunctionResourcePinRepository pinRepository;
  private final PostgresqlCanvasFunctionRunRepository runRepository;
  private final CanvasResourceLifecycle resourceLifecycle;
  private final CanvasCommandPlanner planner;

  public CanvasCommandService(
      PostgresqlCanvasStore canvasStore,
      PostgresqlCanvasResourceRepository resourceRepository,
      PostgresqlCanvasFunctionResourcePinRepository pinRepository,
      PostgresqlCanvasFunctionRunRepository runRepository,
      CanvasResourceLifecycle resourceLifecycle,
      CanvasCommandPlanner planner) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.pinRepository = Objects.requireNonNull(pinRepository, "pinRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.planner = Objects.requireNonNull(planner, "planner");
  }

  /** 创建文档并返回初始 revision；加入调用方事务。 */
  @Transactional
  public CanvasDocument createCanvas(String title) {
    if (title == null) {
      throw DomainException.validation("title", "title must not be null");
    }
    String validated = title.strip();
    if (validated.isEmpty()) {
      throw DomainException.validation("title", "title must not be blank");
    }
    if (validated.length() > MAX_TITLE_LENGTH) {
      throw DomainException.validation(
          "title", "title must not exceed " + MAX_TITLE_LENGTH + " characters");
    }
    for (int index = 0; index < validated.length(); index++) {
      if (Character.isISOControl(validated.charAt(index))) {
        throw DomainException.validation("title", "title must not contain control characters");
      }
    }
    return canvasStore.addDocument(UUID.randomUUID(), validated);
  }

  /** 在调用方事务中深删画布；归属目录由调用方维护。 */
  @Transactional
  public void deleteCanvas(UUID canvasId, long expectedRevision) {
    CanvasDocument document = requireLockedDocument(canvasId);
    if (document.revision() != expectedRevision) {
      throw DomainException.versionConflict("canvas", expectedRevision, document.revision());
    }

    List<NodeRecord> nodes = canvasStore.listNodes(canvasId);
    for (NodeRecord node : nodes) {
      canvasStore.lockNode(canvasId, node.id()).orElseThrow();
    }
    for (NodeRecord node : nodes) {
      runRepository
          .findByNodeIdForUpdate(node.id())
          .ifPresent(
              run -> {
                if (run.status().isUnsettled()) {
                  throw DomainException.conflict(
                      "EXECUTION_NOT_SETTLED",
                      "canvas function run is not settled: " + run.nodeId());
                }
              });
    }
    for (NodeRecord node : nodes) {
      runRepository.lockWork(node.id());
    }

    for (NodeRecord node : nodes) {
      resourceLifecycle.releaseNodePins(canvasId, node.id());
      runRepository.deleteByNodeId(node.id());
    }
    for (CanvasResource resource : resourceRepository.findByCanvasId(canvasId)) {
      resourceLifecycle.discardResource(canvasId, resource.id());
    }
    // 引用连线是节点参数的投影，随节点一并删除。
    for (NodeRecord node : nodes) {
      if (!canvasStore.deleteNode(canvasId, node.id())) {
        throw new IllegalStateException("delete canvas node failed: " + node.id());
      }
    }
    for (CanvasGroup group : canvasStore.listGroups(canvasId)) {
      if (!canvasStore.deleteGroup(canvasId, group.id())) {
        throw new IllegalStateException("delete canvas group failed: " + group.id());
      }
    }
    canvasStore.deleteDocument(canvasId);
  }

  @Transactional
  public CanvasCommandResult applyCommands(
      UUID canvasId, UUID idempotencyKey, List<CanvasCommand> commands) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(commands, "commands");
    String requestHash = CanvasCommandHash.of(commands);
    CanvasDocument document = requireLockedDocument(canvasId);
    CommandDedup dedup = canvasStore.findCommandDedup(canvasId, idempotencyKey).orElse(null);
    if (dedup != null) {
      if (!dedup.requestHash().equals(requestHash)) {
        throw DomainException.conflict(
            "IDEMPOTENCY_CONFLICT", "idempotency key is bound to a different request");
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

  private CanvasDocument requireLockedDocument(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    return canvasStore
        .lockDocument(canvasId)
        .orElseThrow(
            () -> DomainException.notFound("canvas", "canvas document not found: " + canvasId));
  }

  /** 读取 document 行锁内的权威快照，作为命令前沿的判定基础。 */
  private CanvasGraph loadGraph(UUID canvasId) {
    List<CanvasResourceNode> nodes =
        CanvasNodeProjection.project(
            canvasStore.listNodes(canvasId),
            resourceRepository.findByCanvasId(canvasId),
            runRepository.findByCanvasId(canvasId));
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
        case CanvasMutation.DeleteFunctionRun delete -> runRepository.deleteByNodeId(
            delete.nodeId());
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
