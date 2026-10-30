package fun.fengwk.kkstudio.platform.storage.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code session_blob_ref} 的 JdbcClient 仓库：Session 与 blob 的显式引用边。 */
@Repository
public class SessionBlobRefRepository {

  private final JdbcClient jdbcClient;

  public SessionBlobRefRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /** 幂等插入引用行（{@code on conflict do nothing}）；返回是否新插入。 */
  public boolean insertIfAbsent(UUID sessionId, UUID blobId) {
    return jdbcClient
            .sql(
                """
                insert into session_blob_ref (session_id, blob_id)
                values (:sessionId, :blobId)
                on conflict (session_id, blob_id) do nothing
                """)
            .param("sessionId", sessionId)
            .param("blobId", blobId)
            .update()
        == 1;
  }

  /** 删除引用行；返回是否删除。 */
  public boolean delete(UUID sessionId, UUID blobId) {
    return jdbcClient
            .sql("delete from session_blob_ref where session_id = :sessionId and blob_id = :blobId")
            .param("sessionId", sessionId)
            .param("blobId", blobId)
            .update()
        == 1;
  }

  /** 该 Session 是否已引用指定 blob。 */
  public boolean exists(UUID sessionId, UUID blobId) {
    return jdbcClient
        .sql(
            "select exists (select 1 from session_blob_ref where session_id = :sessionId and blob_id = :blobId)")
        .param("sessionId", sessionId)
        .param("blobId", blobId)
        .query(Boolean.class)
        .single();
  }

  /** 该 Session 引用的全部 blob id（深删除与对账用）。 */
  public List<UUID> listBlobIds(UUID sessionId) {
    return jdbcClient
        .sql("select blob_id from session_blob_ref where session_id = :sessionId order by blob_id")
        .param("sessionId", sessionId)
        .query(UUID.class)
        .list();
  }
}
