package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

/**
 * {@code storage_object_cleanup} 的 JdbcClient 入口。
 *
 * <p>到期判定与下次尝试时间都由数据库时间驱动（{@code statement_timestamp()}），调用方只提供「延后多少毫秒」这一非负时长； 认领用 {@code for
 * update skip locked} 让多节点互不阻塞也不重复处理同一批 key。
 */
@Repository
public class StorageObjectCleanupJdbcStore {

  private static final String CLAIM_DUE_SQL =
      """
      update storage_object_cleanup
      set next_attempt_at = date_trunc('milliseconds',
          statement_timestamp() + :delayMillis::double precision * interval '1 millisecond')
      where key in (
          select key
          from storage_object_cleanup
          where next_attempt_at <= date_trunc('milliseconds', statement_timestamp())
          order by next_attempt_at
          limit :limit
          for update skip locked
      )
      returning key
      """;

  private final JdbcClient jdbcClient;

  public StorageObjectCleanupJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public int insertIfAbsent(String key) {
    return jdbcClient
        .sql(
            """
            insert into storage_object_cleanup (key, next_attempt_at)
            values (:key, current_timestamp)
            on conflict (key) do nothing
            """)
        .param("key", key)
        .update();
  }

  public List<String> claimDue(int limit, long delayMillis) {
    return jdbcClient
        .sql(CLAIM_DUE_SQL)
        .param("limit", limit)
        .param("delayMillis", delayMillis)
        .query(String.class)
        .list();
  }

  public int reschedule(String key, long delayMillis) {
    return jdbcClient
        .sql(
            """
            update storage_object_cleanup
            set next_attempt_at = date_trunc('milliseconds',
                statement_timestamp() + :delayMillis::double precision * interval '1 millisecond')
            where key = :key
            """)
        .param("key", key)
        .param("delayMillis", delayMillis)
        .update();
  }
}
