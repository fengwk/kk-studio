package fun.fengwk.kkstudio.canvas;

import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasStore;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Canvas document lifecycle and title summaries. */
public class CanvasDocuments {
  private static final int MAX_TITLE_LENGTH = 256;

  private final PostgresqlCanvasStore canvasStore;
  private final PostgresqlCanvasResourceRepository resourceRepository;
  private final PostgresqlCanvasFunctionRunRepository runRepository;
  private final CanvasResourceLifecycle resourceLifecycle;

  public CanvasDocuments(
      PostgresqlCanvasStore canvasStore,
      PostgresqlCanvasResourceRepository resourceRepository,
      PostgresqlCanvasFunctionRunRepository runRepository,
      CanvasResourceLifecycle resourceLifecycle) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
  }

  @Transactional
  public CanvasDocument create(UUID canvasId, String title) {
    return canvasStore.addDocument(
        Objects.requireNonNull(canvasId, "canvasId"), validateTitle(title));
  }

  @Transactional
  public void rename(UUID canvasId, String title) {
    canvasStore.renameDocument(canvasId, validateTitle(title));
  }

  /** 按 document → node → run → work 锁序深删；调用方维护目录归属。 */
  @Transactional
  public void delete(UUID canvasId, long expectedRevision) {
    CanvasDocument document = canvasStore.requireLockedDocument(canvasId);
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

  @Transactional(readOnly = true)
  public Map<UUID, String> findTitles(Collection<UUID> ids) {
    return canvasStore.findDocumentTitles(ids);
  }

  private static String validateTitle(String title) {
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
    return validated;
  }
}
