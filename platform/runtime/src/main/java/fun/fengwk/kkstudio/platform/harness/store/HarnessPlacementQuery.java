package fun.fengwk.kkstudio.platform.harness.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Narrow product read model; JDBC participates in the caller's Spring snapshot. */
@Repository
public class HarnessPlacementQuery {
  private final JdbcClient jdbc;

  public HarnessPlacementQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record SessionTitle(UUID id, String title) {}

  public record PublicThread(UUID id, UUID sessionId, UUID rootId, String title, long version) {}

  public List<SessionTitle> sessions(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("select id, name as title from harness_session where id in (:ids)")
        .param("ids", ids)
        .query(SessionTitle.class)
        .list();
  }

  public List<PublicThread> threads(Collection<UUID> sessionIds) {
    if (sessionIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
        select id, session_id, root_thread_id as root_id, name as title, version
        from harness_thread where session_id in (:ids) and kind = 'USER' and parent_thread_id is null
        order by created_at, id
        """)
        .param("ids", sessionIds)
        .query(PublicThread.class)
        .list();
  }

  public Optional<PublicThread> thread(UUID id) {
    return jdbc.sql(
            """
        select id, session_id, root_thread_id as root_id, name as title, version
        from harness_thread where id = :id and kind = 'USER' and parent_thread_id is null
        """)
        .param("id", id)
        .query(PublicThread.class)
        .optional();
  }
}
