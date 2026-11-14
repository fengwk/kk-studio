package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.spi.store.EnvironmentToolWaitRow;
import fun.fengwk.kkstudio.harness.spi.store.PendingEnvironmentWaitRow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 「等待环境」的只读查询：把 {@code harness_tool_invocation} 与 {@code durable_work} 的等待事实聚合为待处理投影。
 *
 * <p>全部查询只读、不产生锁，时间条件统一使用数据库时钟 {@code statement_timestamp()}，与 Work claim 的 due / lease 判定处于同一
 * 时间域；这些投影只用于提示刷新，绝不创建或推进任何执行事实。
 */
final class PostgresqlEnvironmentToolWaits {

  /**
   * 「等待环境」只读聚合查询：按 {@code (真实执行根, 冻结的所需环境)} 分组，代表取组内最早 {@code (created_at, id)}。
   *
   * <p>候选是「调用已 READY、其 TOOL Work 已到期且无有效执行 lease、冻结了非空 {@code required_environment_id}」的调用； 根取自
   * Thread 不可变的 {@code root_thread_id}，分组后按代表 keyset 过滤并升序输出。
   */
  private static final String LIST_PENDING_ENVIRONMENT_WAITS =
      """
      with waiting as (
          select i.id as invocation_id,
                 i.created_at as created_at,
                 t.root_thread_id as root_thread_id,
                 w.required_environment_id as environment_id
          from harness_tool_invocation i
          join harness_thread t on t.id = i.thread_id
          join durable_work w on w.target_type = 'TOOL' and w.target_id = i.id
          where i.status = 'READY'
            and w.required_environment_id is not null
            and w.available_at <= statement_timestamp()
            and (w.lease_until is null or w.lease_until <= statement_timestamp())
      ),
      grouped as (
          select root_thread_id,
                 environment_id,
                 min(created_at) as representative_created_at,
                 (array_agg(invocation_id order by created_at, invocation_id))[1]
                     as representative_invocation_id,
                 count(*) as waiting_count
          from waiting
          group by root_thread_id, environment_id
      )
      select root_thread_id,
             environment_id,
             representative_created_at,
             representative_invocation_id,
             waiting_count
      from grouped
      where (representative_created_at, representative_invocation_id) > (?, ?)
      order by representative_created_at, representative_invocation_id
      limit ?
      """;

  /** 环境 Work 可领取性仅因时间推移最早可能改变的权威时刻。 */
  private static final String NEXT_ENVIRONMENT_WORK_CHANGE =
      """
      select least(
                 min(case when w.available_at > statement_timestamp() then w.available_at end),
                 min(case when w.lease_until > statement_timestamp() then w.lease_until end)
             ) as next_change_at
      from harness_tool_invocation i
      join durable_work w on w.target_type = 'TOOL' and w.target_id = i.id
      where i.status = 'READY'
        and w.required_environment_id is not null
      """;

  private final PostgresqlHarnessTransaction tx;

  PostgresqlEnvironmentToolWaits(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  List<PendingEnvironmentWaitRow> listCandidates(
      Instant afterRepresentativeCreatedAt, UUID afterRepresentativeInvocationId, int limit) {
    tx.checkOpen();
    Objects.requireNonNull(afterRepresentativeCreatedAt, "afterRepresentativeCreatedAt");
    Objects.requireNonNull(afterRepresentativeInvocationId, "afterRepresentativeInvocationId");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return tx.queryList(
        LIST_PENDING_ENVIRONMENT_WAITS,
        PostgresqlHarnessRows.PENDING_ENVIRONMENT_WAIT,
        PostgresqlHarnessRows.timestamp(afterRepresentativeCreatedAt),
        afterRepresentativeInvocationId,
        limit);
  }

  /** 指定调用的等待事实；空集合直接返回空结果，不构造空 IN 子句。 */
  List<EnvironmentToolWaitRow> listWaits(Collection<UUID> invocationIds) {
    tx.checkOpen();
    Objects.requireNonNull(invocationIds, "invocationIds");
    List<UUID> ids = List.copyOf(invocationIds);
    if (ids.isEmpty()) {
      return List.of();
    }
    return tx.queryList(
        """
        select i.id as invocation_id,
               w.required_environment_id,
               i.status = 'READY' as invocation_ready,
               case when i.status = 'READY' and w.required_environment_id is not null then
                 least(
                   case when w.available_at > statement_timestamp() then w.available_at end,
                   case when w.lease_until > statement_timestamp() then w.lease_until end
                 )
               end as work_freshness_at,
               i.status = 'READY'
                 and w.target_id is not null
                 and w.required_environment_id is not null
                 and w.available_at <= statement_timestamp()
                 and (w.lease_until is null or w.lease_until <= statement_timestamp())
                 as work_available
        from harness_tool_invocation i
        left join durable_work w on w.target_type = 'TOOL' and w.target_id = i.id
        where i.id in (%s)
        """
            .formatted(PostgresqlHarnessTransaction.placeholders(ids.size())),
        PostgresqlHarnessRows.ENVIRONMENT_TOOL_WAIT,
        ids.toArray());
  }

  List<EnvironmentId> listReadyEnvironments() {
    tx.checkOpen();
    return tx.queryList(
        """
        select distinct w.required_environment_id
        from harness_tool_invocation i
        join durable_work w on w.target_type = 'TOOL' and w.target_id = i.id
        where i.status = 'READY' and w.required_environment_id is not null
        """,
        (rs, rowNumber) -> EnvironmentId.of(rs.getObject("required_environment_id", UUID.class)));
  }

  Optional<Instant> findNextWorkChange() {
    tx.checkOpen();
    Timestamp nextChangeAt = tx.queryForObject(NEXT_ENVIRONMENT_WORK_CHANGE, Timestamp.class);
    return Optional.ofNullable(nextChangeAt).map(Timestamp::toInstant);
  }
}
