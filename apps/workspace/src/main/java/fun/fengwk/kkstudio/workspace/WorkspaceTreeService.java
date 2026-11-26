package fun.fengwk.kkstudio.workspace;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasDocuments;
import fun.fengwk.kkstudio.harness.api.thread.ExecutionRootFacts;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.harness.store.HarnessPlacementQuery;
import fun.fengwk.kkstudio.platform.harness.store.HarnessPlacementQuery.PublicThread;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceFolderDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceResourceDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceResourceKind;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceThreadDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceThreadPlacementDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceThreadState;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceThreadStateReason;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceTreeDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkspaceTreeService {
  private final ChatSpaceService spaces;
  private final WorkspacePlacements placements;
  private final HarnessPlacementQuery harness;
  private final ObjectProvider<HarnessRuntime> runtime;
  private final CanvasDocuments canvases;
  private final JdbcClient jdbc;

  WorkspaceTreeService(
      ChatSpaceService spaces,
      WorkspacePlacements placements,
      HarnessPlacementQuery harness,
      ObjectProvider<HarnessRuntime> runtime,
      CanvasDocuments canvases,
      JdbcClient jdbc) {
    this.spaces = spaces;
    this.placements = placements;
    this.harness = harness;
    this.runtime = runtime;
    this.canvases = canvases;
    this.jdbc = jdbc;
  }

  private record FolderRow(UUID id, UUID parentId, String name) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkspaceTreeDTO tree(StudioPrincipal actor, UUID spaceId) {
    var space = spaces.get(actor, spaceId);
    var folders =
        jdbc.sql(
                "select id, parent_id, name from workspace_folder where workspace_id = :space order by position, id")
            .param("space", spaceId)
            .query(FolderRow.class)
            .list();
    var edges = placements.list(spaceId);
    var sessionIds =
        edges.stream()
            .filter(edge -> edge.kind() == Kind.SESSION)
            .map(WorkspacePlacements.Edge::id)
            .toList();
    var canvasIds =
        edges.stream()
            .filter(edge -> edge.kind() == Kind.CANVAS)
            .map(WorkspacePlacements.Edge::id)
            .toList();
    var titles =
        harness.sessions(sessionIds).stream()
            .collect(
                Collectors.toMap(
                    HarnessPlacementQuery.SessionTitle::id,
                    HarnessPlacementQuery.SessionTitle::title));
    var canvasTitles = canvases.findTitles(canvasIds);
    var threads = harness.threads(sessionIds);
    var facts =
        threads.isEmpty()
            ? List.<ExecutionRootFacts>of()
            : runtime
                .getObject()
                .getExecutionRootFacts(threads.stream().map(PublicThread::rootId).toList());
    var byRoot =
        facts.stream()
            .collect(Collectors.toMap(ExecutionRootFacts::rootThreadId, Function.identity()));
    var bySession = new HashMap<UUID, List<WorkspaceThreadDTO>>();
    for (var thread : threads) {
      var root = byRoot.get(thread.rootId());
      var status = ThreadStatus.project(root);
      bySession
          .computeIfAbsent(thread.sessionId(), ignored -> new ArrayList<>())
          .add(
              new WorkspaceThreadDTO(
                  thread.id(),
                  thread.title(),
                  wireState(status.state()),
                  wireReason(status.reason()),
                  Long.toString(thread.version()),
                  root.lastActivityAt()));
    }
    var nodes = new HashMap<UUID, WorkspaceFolderDTO>();
    for (var folder : folders) {
      nodes.put(
          folder.id(),
          new WorkspaceFolderDTO(folder.id(), folder.name(), new ArrayList<>(), new ArrayList<>()));
    }
    var roots = new ArrayList<WorkspaceFolderDTO>();
    for (var folder : folders) {
      (folder.parentId() == null ? roots : nodes.get(folder.parentId()).children())
          .add(nodes.get(folder.id()));
    }
    for (var edge : edges) {
      boolean session = edge.kind() == Kind.SESSION;
      nodes
          .get(edge.folderId())
          .resources()
          .add(
              new WorkspaceResourceDTO(
                  session ? WorkspaceResourceKind.SESSION : WorkspaceResourceKind.CANVAS,
                  edge.id(),
                  session ? titles.get(edge.id()) : canvasTitles.get(edge.id()),
                  session ? bySession.getOrDefault(edge.id(), List.of()) : null));
    }
    return new WorkspaceTreeDTO(Long.toString(space.treeVersion()), roots);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkspaceThreadPlacementDTO thread(StudioPrincipal actor, UUID space, UUID id) {
    spaces.get(actor, space);
    return threadPlacement(space, id);
  }

  WorkspaceThreadPlacementDTO threadPlacement(UUID space, UUID id) {
    var thread = harness.thread(id).orElseThrow(ChatSpaceService::resourceNotFound);
    var edge = placements.require(space, thread.sessionId(), Kind.SESSION);
    var title = harness.sessions(List.of(thread.sessionId())).getFirst().title();
    var status =
        ThreadStatus.project(
            runtime.getObject().getExecutionRootFacts(List.of(thread.rootId())).getFirst());
    return new WorkspaceThreadPlacementDTO(
        thread.sessionId(),
        title,
        edge.folderId(),
        thread.title(),
        wireState(status.state()),
        wireReason(status.reason()),
        Long.toString(thread.version()));
  }

  private static WorkspaceThreadState wireState(ThreadStatus.State state) {
    return switch (state) {
      case NEEDS_YOU -> WorkspaceThreadState.NEEDS_YOU;
      case RUNNING -> WorkspaceThreadState.RUNNING;
      case WAITING -> WorkspaceThreadState.WAITING;
      case FAILED -> WorkspaceThreadState.FAILED;
      case IDLE -> WorkspaceThreadState.IDLE;
    };
  }

  private static @Nullable WorkspaceThreadStateReason wireReason(
      ThreadStatus.@Nullable Reason reason) {
    return switch (reason) {
      case null -> null;
      case STOPPED -> WorkspaceThreadStateReason.STOPPED;
    };
  }
}
