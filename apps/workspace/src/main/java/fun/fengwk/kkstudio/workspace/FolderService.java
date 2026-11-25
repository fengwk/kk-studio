package fun.fengwk.kkstudio.workspace;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.hash.Sha256;
import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Directory mutations serialize on the owning space; all sibling ordering lives on folder edges.
 */
@Service
public class FolderService {
  private final ChatSpaceService spaces;
  private final JdbcClient jdbc;

  public FolderService(ChatSpaceService spaces, JdbcClient jdbc) {
    this.spaces = spaces;
    this.jdbc = jdbc;
  }

  public record Create(UUID parentId, String name, long expectedTreeVersion) {}

  public record Created(UUID id, long treeVersion) {}

  private record Folder(UUID id, UUID parentId) {}

  private record Replay(String createRequestHash, long createdTreeVersion) {}

  @Transactional
  public Created create(StudioPrincipal actor, UUID spaceId, UUID idempotencyKey, Create request) {
    var space = spaces.lock(actor, spaceId);
    if (idempotencyKey == null || request == null) {
      throw DomainException.validationReason(
          "INVALID_ARGUMENT", "Idempotency key and request are required");
    }
    UUID id = ChatSpaceService.derivedId("folder", spaceId, idempotencyKey);
    String hash = Sha256.hex(StrictJson.standard().write(request, "workspace folder creation"));
    var replay =
        jdbc.sql(
                """
        select create_request_hash, created_tree_version from workspace_folder
        where workspace_id = :space and id = :id
        """)
            .param("space", spaceId)
            .param("id", id)
            .query(Replay.class)
            .optional();
    if (replay.isPresent()) {
      var result = replay.get();
      if (!hash.equals(result.createRequestHash())) {
        throw DomainException.conflict(
            "IDEMPOTENCY_KEY_REUSED", "Creation key already used for a different request");
      }
      return new Created(id, result.createdTreeVersion());
    }
    ChatSpaceService.requireTreeVersion(space, request.expectedTreeVersion());
    String name = ChatSpaceService.name(request.name(), "name");
    var folders = folders(spaceId);
    requireParent(folders, request.parentId());
    long nextVersion = space.treeVersion() + 1;
    try {
      jdbc.sql(
              """
          insert into workspace_folder
              (id, workspace_id, parent_id, name, name_key, position, create_request_hash, created_tree_version)
          values (:id, :space, :parent, :name, :key, :position, :hash, :version)
          """)
          .param("id", id)
          .param("space", spaceId)
          .param("parent", request.parentId())
          .param("name", name)
          .param("key", ChatSpaceService.nameKey(name))
          .param("position", siblings(folders, request.parentId()).size())
          .param("hash", hash)
          .param("version", nextVersion)
          .update();
    } catch (DuplicateKeyException ex) {
      throw nameExists();
    }
    return new Created(id, spaces.advance(spaceId));
  }

  @Transactional
  public long rename(StudioPrincipal actor, UUID spaceId, UUID folderId, String requestedName) {
    var space = spaces.lock(actor, spaceId);
    String name = ChatSpaceService.name(requestedName, "name");
    try {
      int updated =
          jdbc.sql(
                  """
          update workspace_folder set name = :name, name_key = :key
          where workspace_id = :space and id = :id
          """)
              .param("space", spaceId)
              .param("id", folderId)
              .param("name", name)
              .param("key", ChatSpaceService.nameKey(name))
              .update();
      if (updated == 0) {
        throw ChatSpaceService.resourceNotFound();
      }
    } catch (DuplicateKeyException ex) {
      throw nameExists();
    }
    return spaces.treeChanged(spaceId, space.treeVersion());
  }

  @Transactional
  public long move(
      StudioPrincipal actor,
      UUID spaceId,
      UUID folderId,
      UUID parentId,
      UUID beforeId,
      long expectedTreeVersion) {
    var space = spaces.lock(actor, spaceId);
    var folders = folders(spaceId);
    var source = requireFolder(folders, folderId);
    ChatSpaceService.requireTreeVersion(space, expectedTreeVersion);
    requireParent(folders, parentId);
    var byId = new HashMap<UUID, Folder>();
    for (var folder : folders) {
      byId.put(folder.id(), folder);
    }
    requireAcyclic(byId, folderId, parentId);
    var sourceSiblings = siblings(folders, source.parentId());
    boolean sameParent = Objects.equals(source.parentId(), parentId);
    if (!sameParent && source.parentId() == null && sourceSiblings.size() == 1) {
      throw lastRoot();
    }
    var destination = sameParent ? sourceSiblings : siblings(folders, parentId);
    if (sameParent && Objects.equals(folderId, beforeId)) {
      return space.treeVersion();
    }
    sourceSiblings.remove(folderId);
    int index = beforeId == null ? destination.size() : destination.indexOf(beforeId);
    if (index < 0) {
      throw invalidFolder();
    }
    destination.add(index, folderId);
    try {
      if (!sameParent) {
        reorder(spaceId, source.parentId(), sourceSiblings);
      }
      reorder(spaceId, parentId, destination);
    } catch (DuplicateKeyException ex) {
      throw nameExists();
    }
    return spaces.advance(spaceId);
  }

  @Transactional
  public long delete(StudioPrincipal actor, UUID spaceId, UUID folderId, long expectedTreeVersion) {
    var space = spaces.lock(actor, spaceId);
    var folders = folders(spaceId);
    var source = requireFolder(folders, folderId);
    ChatSpaceService.requireTreeVersion(space, expectedTreeVersion);
    boolean hasResources =
        jdbc.sql(
                """
        select exists(select 1 from workspace_resource where workspace_id = :space and folder_id = :id)
        """)
            .param("space", spaceId)
            .param("id", folderId)
            .query(Boolean.class)
            .single();
    if (hasResources || folders.stream().anyMatch(folder -> folderId.equals(folder.parentId()))) {
      throw DomainException.conflict("FOLDER_NOT_EMPTY", "The folder is not empty");
    }
    var siblings = siblings(folders, source.parentId());
    if (source.parentId() == null && siblings.size() == 1) {
      throw lastRoot();
    }
    jdbc.sql("delete from workspace_folder where workspace_id = :space and id = :id")
        .param("space", spaceId)
        .param("id", folderId)
        .update();
    siblings.remove(folderId);
    reorder(spaceId, source.parentId(), siblings);
    return spaces.advance(spaceId);
  }

  private List<Folder> folders(UUID spaceId) {
    return jdbc.sql(
            """
        select id, parent_id from workspace_folder where workspace_id = :space order by position, id
        """)
        .param("space", spaceId)
        .query(Folder.class)
        .list();
  }

  private static Folder requireFolder(List<Folder> folders, UUID id) {
    return folders.stream()
        .filter(folder -> folder.id().equals(id))
        .findFirst()
        .orElseThrow(() -> ChatSpaceService.resourceNotFound());
  }

  private static void requireParent(List<Folder> folders, UUID parentId) {
    if (parentId != null && folders.stream().noneMatch(folder -> folder.id().equals(parentId))) {
      throw invalidFolder();
    }
  }

  private static void requireAcyclic(Map<UUID, Folder> byId, UUID folderId, UUID parentId) {
    for (UUID ancestor = parentId; ancestor != null; ancestor = byId.get(ancestor).parentId()) {
      if (ancestor.equals(folderId)) {
        throw invalidFolder();
      }
    }
  }

  private static List<UUID> siblings(List<Folder> folders, UUID parentId) {
    var ids = new ArrayList<UUID>();
    for (Folder folder : folders) {
      if (Objects.equals(folder.parentId(), parentId)) {
        ids.add(folder.id());
      }
    }
    return ids;
  }

  private void reorder(UUID spaceId, UUID parentId, List<UUID> siblings) {
    if (siblings.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
        update workspace_folder f set parent_id = :parent, position = ordered.n - 1
        from unnest(cast(:ids as uuid[])) with ordinality as ordered(id, n)
        where f.workspace_id = :space and f.id = ordered.id
        """)
        .param("parent", parentId)
        .param("ids", siblings.toArray(UUID[]::new))
        .param("space", spaceId)
        .update();
  }

  static DomainException invalidFolder() {
    return DomainException.validationReason(
        "INVALID_FOLDER", "The folder is not a valid destination");
  }

  private static DomainException nameExists() {
    return DomainException.conflict(
        "FOLDER_NAME_EXISTS", "A sibling folder already uses this name");
  }

  private static DomainException lastRoot() {
    return DomainException.conflict("LAST_ROOT_FOLDER", "At least one root folder must remain");
  }
}
