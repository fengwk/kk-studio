package fun.fengwk.kkstudio.workspace;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.hash.Sha256;
import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioUserLock;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Space configuration and the lock boundary for all workspace structure mutations. */
@Service
public class ChatSpaceService {
  private final JdbcClient jdbc;
  private final StudioUserLock userLock;

  public ChatSpaceService(JdbcClient jdbc, StudioUserLock userLock) {
    this.jdbc = jdbc;
    this.userLock = userLock;
  }

  public record Space(
      UUID id,
      String title,
      OffsetDateTime archivedAt,
      long version,
      long treeVersion,
      String defaultAgentName,
      UUID defaultEnvironmentId,
      boolean defaultYoloEnabled) {}

  public record Create(
      String title,
      String rootFolderName,
      String defaultAgentName,
      UUID defaultEnvironmentId,
      boolean defaultYoloEnabled) {}

  /** A null change leaves the field untouched; a change with a null value clears it. */
  public record Change<T>(T value) {}

  public record Patch(
      long expectedVersion,
      String title,
      Boolean archived,
      Change<String> defaultAgentName,
      Change<UUID> defaultEnvironmentId,
      Boolean defaultYoloEnabled) {}

  public List<Space> list(StudioPrincipal actor) {
    return jdbc.sql(
            """
        select id, title, archived_at, version, tree_version, default_agent_name,
               default_environment_id, default_yolo_enabled
        from chat_space where owner_user_id = :owner
        order by archived_at nulls first, title_key, id
        """)
        .param("owner", actor.userId())
        .query(Space.class)
        .list();
  }

  @Transactional
  public Space create(StudioPrincipal actor, UUID idempotencyKey, Create request) {
    if (idempotencyKey == null || request == null) {
      throw DomainException.validationReason(
          "INVALID_ARGUMENT", "Idempotency key and request are required");
    }
    UUID owner = actor.userId();
    UUID id = derivedId("space", owner, idempotencyKey);
    String hash = Sha256.hex(StrictJson.standard().write(request, "chat space creation"));
    userLock.acquire(owner);
    var originalHash =
        jdbc.sql(
                """
        select create_request_hash from chat_space where id = :id and owner_user_id = :owner
        """)
            .param("id", id)
            .param("owner", owner)
            .query(String.class)
            .optional();
    if (originalHash.isPresent() && !originalHash.get().equals(hash)) {
      throw DomainException.conflict(
          "IDEMPOTENCY_KEY_REUSED", "Creation key already used for a different request");
    }
    String title = name(request.title(), "title");
    String root = name(request.rootFolderName(), "rootFolderName");
    if (originalHash.isEmpty()) {
      try {
        jdbc.sql(
                """
            insert into chat_space (id, owner_user_id, title, title_key, default_agent_name,
                default_environment_id, default_yolo_enabled, create_request_hash)
            values (:id, :owner, :title, :key, :agent, :environment, :yolo, :hash)
            """)
            .param("id", id)
            .param("owner", owner)
            .param("title", title)
            .param("key", nameKey(title))
            .param("agent", request.defaultAgentName())
            .param("environment", request.defaultEnvironmentId())
            .param("yolo", request.defaultYoloEnabled())
            .param("hash", hash)
            .update();
      } catch (DuplicateKeyException ex) {
        throw titleExists();
      } catch (DataIntegrityViolationException ex) {
        throw invalidDefaults();
      }
      jdbc.sql(
              """
          insert into workspace_folder (id, workspace_id, name, name_key, position)
          values (:folder, :space, :name, :key, 0)
          """)
          .param("folder", derivedId("root-folder", owner, idempotencyKey))
          .param("space", id)
          .param("name", root)
          .param("key", nameKey(root))
          .update();
    }
    // Reconstruct the original response, not the subsequently edited space.
    return new Space(
        id,
        title,
        null,
        0,
        0,
        request.defaultAgentName(),
        request.defaultEnvironmentId(),
        request.defaultYoloEnabled());
  }

  @Transactional
  public Space patch(StudioPrincipal actor, UUID id, Patch patch) {
    if (patch.archived() != null) {
      userLock.acquire(actor.userId());
    }
    Space old = lock(actor, id);
    if (old.version() != patch.expectedVersion()) {
      throw DomainException.versionConflict("chat-space", patch.expectedVersion(), old.version());
    }
    String title = patch.title() == null ? old.title() : name(patch.title(), "title");
    OffsetDateTime archivedAt = old.archivedAt();
    if (Boolean.TRUE.equals(patch.archived()) && archivedAt == null) {
      boolean hasOther =
          jdbc.sql(
                  """
          select exists(select 1 from chat_space
                        where owner_user_id = :owner and id <> :id and archived_at is null)
          """)
              .param("owner", actor.userId())
              .param("id", id)
              .query(Boolean.class)
              .single();
      if (!hasOther) {
        throw DomainException.conflict(
            "LAST_ACTIVE_CHAT_SPACE", "At least one space must remain active");
      }
      archivedAt = jdbc.sql("select current_timestamp(3)").query(OffsetDateTime.class).single();
    } else if (Boolean.FALSE.equals(patch.archived())) {
      archivedAt = null;
    }
    String agent =
        patch.defaultAgentName() == null
            ? old.defaultAgentName()
            : patch.defaultAgentName().value();
    UUID environment =
        patch.defaultEnvironmentId() == null
            ? old.defaultEnvironmentId()
            : patch.defaultEnvironmentId().value();
    boolean yolo =
        patch.defaultYoloEnabled() == null ? old.defaultYoloEnabled() : patch.defaultYoloEnabled();
    try {
      return jdbc.sql(
              """
          update chat_space set title = :title, title_key = :key, archived_at = :archived,
              default_agent_name = :agent, default_environment_id = :environment,
              default_yolo_enabled = :yolo, version = version + 1
          where id = :id
          returning id, title, archived_at, version, tree_version, default_agent_name,
                    default_environment_id, default_yolo_enabled
          """)
          .param("title", title)
          .param("key", nameKey(title))
          .param("archived", archivedAt)
          .param("agent", agent)
          .param("environment", environment)
          .param("yolo", yolo)
          .param("id", id)
          .query(Space.class)
          .single();
    } catch (DuplicateKeyException ex) {
      throw titleExists();
    } catch (DataIntegrityViolationException ex) {
      throw invalidDefaults();
    }
  }

  Space get(StudioPrincipal actor, UUID id) {
    return find(actor, id, false);
  }

  Space lock(StudioPrincipal actor, UUID id) {
    return find(actor, id, true);
  }

  private Space find(StudioPrincipal actor, UUID id, boolean lock) {
    return jdbc.sql(
            """
        select id, title, archived_at, version, tree_version, default_agent_name,
               default_environment_id, default_yolo_enabled
        from chat_space where id = :id and owner_user_id = :owner
        """
                + (lock ? " for update" : ""))
        .param("id", id)
        .param("owner", actor.userId())
        .query(Space.class)
        .optional()
        .orElseThrow(
            () -> DomainException.notFoundReason("WORKSPACE_NOT_FOUND", "Space not found"));
  }

  long advance(UUID spaceId) {
    return jdbc.sql(
            "update chat_space set tree_version = tree_version + 1 where id = :id returning tree_version")
        .param("id", spaceId)
        .query(Long.class)
        .single();
  }

  static void requireTreeVersion(Space space, long expected) {
    if (space.treeVersion() != expected) {
      throw DomainException.versionConflict("workspace-tree", expected, space.treeVersion());
    }
  }

  static DomainException resourceNotFound() {
    return DomainException.notFoundReason("RESOURCE_NOT_FOUND", "Resource not found");
  }

  static String name(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()
        || normalized.length() > 256
        || normalized.chars().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(
          field, "Expected a nonblank name of at most 256 characters without controls");
    }
    return normalized;
  }

  static String nameKey(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  static UUID derivedId(String kind, UUID scope, UUID key) {
    return UUID.nameUUIDFromBytes(
        (kind + ":" + scope + ":" + key).getBytes(StandardCharsets.UTF_8));
  }

  private static DomainException titleExists() {
    return DomainException.conflict("CHAT_SPACE_TITLE_EXISTS", "A space already uses this title");
  }

  private static DomainException invalidDefaults() {
    return DomainException.validationReason(
        "INVALID_ARGUMENT", "The default agent or environment does not exist");
  }
}
