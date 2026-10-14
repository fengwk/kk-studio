package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code session_blob_ref} 的 JdbcClient 入口。 */
@Repository
public class SessionBlobRefJdbcStore {

  private final JdbcClient jdbcClient;

  public SessionBlobRefJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public int insertIfAbsent(UUID sessionId, UUID blobId) {
    return jdbcClient
        .sql(
            """
            insert into session_blob_ref (session_id, blob_id)
            values (:sessionId, :blobId)
            on conflict (session_id, blob_id) do nothing
            """)
        .param("sessionId", sessionId)
        .param("blobId", blobId)
        .update();
  }

  public int delete(UUID sessionId, UUID blobId) {
    return jdbcClient
        .sql("delete from session_blob_ref where session_id = :sessionId and blob_id = :blobId")
        .param("sessionId", sessionId)
        .param("blobId", blobId)
        .update();
  }

  public boolean exists(UUID sessionId, UUID blobId) {
    return jdbcClient
        .sql(
            "select exists (select 1 from session_blob_ref where session_id = :sessionId and blob_id = :blobId)")
        .param("sessionId", sessionId)
        .param("blobId", blobId)
        .query(Boolean.class)
        .single();
  }

  public List<UUID> listBlobIds(UUID sessionId) {
    return jdbcClient
        .sql("select blob_id from session_blob_ref where session_id = :sessionId order by blob_id")
        .param("sessionId", sessionId)
        .query(UUID.class)
        .list();
  }
}
