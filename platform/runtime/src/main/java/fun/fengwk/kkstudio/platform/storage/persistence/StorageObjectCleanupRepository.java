package fun.fengwk.kkstudio.platform.storage.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

/**
 * {@code storage_object_cleanup} 的 JdbcClient 仓库：对象 key 的耐久清理记录。
 *
 * <p>插入幂等（key 是主键），必须与「移除该对象最后一个数据库事实」的变更在同一事务内提交；记录永不按时间删除，清扫只推进下一次尝试时间。
 *
 * <p>所有时间推进都由数据库时间加调用方给出的非负毫秒延后得到，调用方不提供绝对时间点：节点 JVM 时钟偏移不会把下次尝试时间推到过去。
 */
@Repository
public class StorageObjectCleanupRepository {

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

  public StorageObjectCleanupRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /** 幂等登记一个清理 key（已存在则保留原记录，不覆盖其下次尝试时间）。 */
  public boolean insertIfAbsent(String key) {
    // on conflict do nothing 时返回 0：key 已在登记事实中，仍然满足「对象被清理」的耐久证据。
    return jdbcClient
            .sql(
                """
                insert into storage_object_cleanup (key, next_attempt_at)
                values (:key, current_timestamp)
                on conflict (key) do nothing
                """)
            .param("key", key)
            .update()
        == 1;
  }

  /**
   * 原子认领到期批次：按数据库当前时间选出至多 {@code limit} 条到期记录（{@code for update skip locked} 与其他节点互斥）， 并把它们的 {@code
   * next_attempt_at} 统一推进为「数据库当前时间 + {@code delayMillis} 毫秒」后返回被认领的 key。
   *
   * @param delayMillis 非负的延后毫秒数（0 表示立即可再次到期）
   */
  public List<String> claimDue(int limit, long delayMillis) {
    return jdbcClient
        .sql(CLAIM_DUE_SQL)
        .param("limit", limit)
        .param("delayMillis", delayMillis)
        .query(String.class)
        .list();
  }

  /** 认领后的对象删除失败：把该 key 的下次尝试时间改为「数据库当前时间 + {@code delayMillis} 毫秒」。 */
  public boolean reschedule(String key, long delayMillis) {
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
            .update()
        == 1;
  }
}
