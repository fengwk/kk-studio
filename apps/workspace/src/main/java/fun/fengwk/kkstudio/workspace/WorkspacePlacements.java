package fun.fengwk.kkstudio.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.hash.Sha256;
import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resource edges and their ordered membership. Write callers hold the space lock. */
@Repository
class WorkspacePlacements {
  private final JdbcClient jdbc;

  WorkspacePlacements(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  record Edge(
      UUID id,
      UUID folderId,
      UUID sessionId,
      String agentName,
      String createRequestHash,
      Long createdTreeVersion,
      UUID initialCommandId) {
    Kind kind() {
      return sessionId == null ? Kind.CANVAS : Kind.SESSION;
    }
  }

  private static final String COLUMNS =
      "id, folder_id, session_id, agent_name, create_request_hash, created_tree_version, initial_command_id";

  Optional<Edge> find(UUID space, UUID id) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from workspace_resource where workspace_id = :space and id = :id")
        .param("space", space)
        .param("id", id)
        .query(Edge.class)
        .optional();
  }

  Edge require(UUID space, UUID id, Kind kind) {
    return find(space, id)
        .filter(edge -> edge.kind() == kind)
        .orElseThrow(ChatSpaceService::resourceNotFound);
  }

  List<Edge> list(UUID space) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from workspace_resource where workspace_id = :space order by position, id")
        .param("space", space)
        .query(Edge.class)
        .list();
  }

  static String fingerprint(Object request) {
    return Sha256.hex(StrictJson.standard().write(request, "workspace creation"));
  }

  Optional<Edge> replay(UUID space, UUID id, String hash) {
    return find(space, id)
        .map(
            edge -> {
              if (!hash.equals(edge.createRequestHash())) {
                throw DomainException.conflict(
                    "IDEMPOTENCY_KEY_REUSED", "Creation key already used for a different request");
              }
              return edge;
            });
  }

  void requireFolder(UUID space, UUID folder) {
    if (folder == null
        || jdbc.sql("select id from workspace_folder where workspace_id = :space and id = :folder")
            .param("space", space)
            .param("folder", folder)
            .query(UUID.class)
            .optional()
            .isEmpty()) {
      throw FolderService.invalidFolder();
    }
  }

  void insert(
      UUID space,
      UUID folder,
      UUID id,
      Kind kind,
      String agent,
      String hash,
      long version,
      UUID command) {
    jdbc.sql(
            "update workspace_resource set position = position + 1 where workspace_id = :space and folder_id = :folder")
        .param("space", space)
        .param("folder", folder)
        .update();
    jdbc.sql(
            """
        insert into workspace_resource (id, workspace_id, folder_id, position, session_id, canvas_id,
            agent_name, create_request_hash, created_tree_version, initial_command_id)
        values (:id, :space, :folder, 0, :session, :canvas, :agent, :hash, :version, :command)
        """)
        .param("id", id)
        .param("space", space)
        .param("folder", folder)
        .param("session", kind == Kind.SESSION ? id : null)
        .param("canvas", kind == Kind.CANVAS ? id : null)
        .param("agent", agent)
        .param("hash", hash)
        .param("version", version)
        .param("command", command)
        .update();
  }

  void move(UUID space, Edge edge, UUID target, UUID before) {
    UUID folder = target == null ? edge.folderId() : target;
    requireFolder(space, folder);
    boolean same = folder.equals(edge.folderId());
    if (same && edge.id().equals(before)) {
      return;
    }
    var source = siblings(space, edge.folderId());
    var destination = same ? source : siblings(space, folder);
    source.remove(edge.id());
    int index = before == null ? (same ? destination.size() : 0) : destination.indexOf(before);
    if (index < 0) {
      throw FolderService.invalidFolder();
    }
    destination.add(index, edge.id());
    if (!same) {
      reorder(space, edge.folderId(), source);
    }
    reorder(space, folder, destination);
  }

  void delete(UUID space, Edge edge) {
    jdbc.sql("delete from workspace_resource where workspace_id = :space and id = :id")
        .param("space", space)
        .param("id", edge.id())
        .update();
    reorder(space, edge.folderId(), siblings(space, edge.folderId()));
  }

  private List<UUID> siblings(UUID space, UUID folder) {
    return new ArrayList<>(
        jdbc.sql(
                """
        select id from workspace_resource where workspace_id = :space and folder_id = :folder order by position
        """)
            .param("space", space)
            .param("folder", folder)
            .query(UUID.class)
            .list());
  }

  private void reorder(UUID space, UUID folder, List<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
        update workspace_resource r set folder_id = :folder, position = ordered.n - 1
        from unnest(cast(:ids as uuid[])) with ordinality as ordered(id, n)
        where r.workspace_id = :space and r.id = ordered.id
        """)
        .param("space", space)
        .param("folder", folder)
        .param("ids", ids.toArray(UUID[]::new))
        .update();
  }
}
