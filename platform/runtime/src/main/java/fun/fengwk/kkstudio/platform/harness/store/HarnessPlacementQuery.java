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

  /** Durable inputs and open turns remain obligations even if their Work is between transitions. */
  public boolean hasPendingInputOrTurn(UUID root) {
    return jdbc.sql(
            """
        with recursive members as (
          select id, head_entry_id, input_through_sequence
          from harness_thread where root_thread_id = :root
        ), tails(thread_id, entry_type, parent_entry_id, payload) as (
          select m.id, e.entry_type, e.parent_entry_id, e.payload
          from members m join harness_entry e on e.id = m.head_entry_id
          union all
          select t.thread_id, e.entry_type, e.parent_entry_id, e.payload
          from tails t join harness_entry e on e.id = t.parent_entry_id
          where t.entry_type in ('NOTIFICATION', 'SETTINGS', 'NATIVE_COMPACTION', 'FORK', 'SYSTEM')
        )
        select exists (
          select 1 from members m join harness_thread_command c on c.thread_id = m.id
          where c.cancelled_at is null and (c.applied_entry_id is null or
             (c.command_type = 'NOTIFICATION' and c.sequence > m.input_through_sequence))
        ) or exists (
          select 1 from tails t
          where t.entry_type not in ('ROOT','TURN_END','NOTIFICATION','SETTINGS',
             'NATIVE_COMPACTION','FORK','SYSTEM')
          or (t.entry_type = 'TURN_END' and t.payload ->> 'continueModel' = 'true' and exists (
            select 1 from harness_entry s
            where s.id = cast(t.payload ->> 'turnStartEntryId' as uuid)
              and s.payload ->> 'ownerThreadId' = cast(t.thread_id as text)
          ))
        )
        """)
        .param("root", root)
        .query(Boolean.class)
        .single();
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
