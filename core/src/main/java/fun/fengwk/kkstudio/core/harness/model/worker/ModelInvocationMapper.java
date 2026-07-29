package fun.fengwk.kkstudio.core.harness.model.worker;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * final-schema {@code harness_model_invocation} 适配器专用 mapper。
 *
 * <p>本 mapper 仅供 {@link PostgresqlModelInvocationTransactions} 使用。所有 claim-following mutation 都在
 * SQL 中自带完整 CAS 谓词（id, thread_id, status, execution_epoch, attempt, worker_token, worker_until >
 * now），由 PostgreSQL 作为 owner 校验的最终权威。jsonb 写入使用 {@code cast(? as jsonb)}。
 */
@Mapper
public interface ModelInvocationMapper extends BaseMapper {

  String SELECT_FIELDS =
      """
       mi.id as id, mi.thread_id as thread_id,
      mi.source_head_entry_id as source_head_entry_id, mi.execution_epoch as execution_epoch,
      mi.request as request, mi.status as status, mi.attempt as attempt,
      mi.next_attempt_at as next_attempt_at, mi.worker_token as worker_token,
      mi.worker_until as worker_until, mi.deadline_at as deadline_at,
      mi.last_activity_at as last_activity_at, mi.result as result, mi.error as error,
      mi.applied_at as applied_at, mi.created_at as created_at,
      mi.started_at as started_at, mi.finished_at as finished_at,
      mi.safe_stream_snapshot as safe_stream_snapshot
      """;

  /**
   * 锁定并取出一行 Invocation 用于校验 CAS 谓词；只在本 adapter 内部进行 FOR UPDATE。 还会校验 invocation 的 thread_id
   * 是否与外部传入一致。
   */
  @Select(
      """
      select """
          + SELECT_FIELDS
          + """
      from harness_model_invocation mi
      where mi.id = #{id} and mi.thread_id = #{threadId}
      for update
      """)
  @Results(
      id = "modelInvocationResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "source_head_entry_id", property = "sourceHeadEntryId"),
        @Result(column = "execution_epoch", property = "executionEpoch"),
        @Result(column = "request", property = "requestJson"),
        @Result(column = "status", property = "status"),
        @Result(column = "attempt", property = "attempt"),
        @Result(column = "next_attempt_at", property = "nextAttemptAt"),
        @Result(column = "worker_token", property = "workerToken"),
        @Result(column = "worker_until", property = "workerUntil"),
        @Result(column = "deadline_at", property = "deadlineAt"),
        @Result(column = "last_activity_at", property = "lastActivityAt"),
        @Result(column = "result", property = "resultJson"),
        @Result(column = "error", property = "errorJson"),
        @Result(column = "applied_at", property = "appliedAt"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "started_at", property = "startedAt"),
        @Result(column = "finished_at", property = "finishedAt"),
        @Result(column = "safe_stream_snapshot", property = "safeStreamSnapshotJson")
      })
  ModelInvocationDO findForUpdate(@Param("id") long id, @Param("threadId") long threadId);

  /** snapshot-first 只读：按 Thread 列出全部 ModelInvocation。 */
  @Select(
      "select "
          + SELECT_FIELDS
          + " from harness_model_invocation mi where mi.thread_id = #{threadId}"
          + " order by mi.created_at, mi.id")
  @ResultMap("modelInvocationResultMap")
  List<ModelInvocationDO> listByThread(@Param("threadId") long threadId);

  /** snapshot-first 只读：按 id 查 ModelInvocation。 */
  @Select("select " + SELECT_FIELDS + " from harness_model_invocation mi where mi.id = #{id}")
  @ResultMap("modelInvocationResultMap")
  ModelInvocationDO find(@Param("id") long id);

  /**
   * peek：检查 id 对应 Invocation 是否可被 claim，且当前 owning Thread 的 executionEpoch 与
   * invocation.executionEpoch 一致。返回完整 row 让 adapter 决定是否能进入 claim 事务。
   */
  @Select(
      """
      select """
          + SELECT_FIELDS
          + """
      from harness_model_invocation mi
      join harness_thread t on t.id = mi.thread_id
      where mi.id = #{id}
        and t.execution_epoch = mi.execution_epoch
        and (
          mi.status = 'QUEUED'
          or (mi.status = 'RETRY_WAIT' and mi.next_attempt_at <= #{now})
          or (mi.status = 'RUNNING' and mi.worker_until <= #{now})
        )
      """)
  @ResultMap("modelInvocationResultMap")
  ModelInvocationDO findClaimable(@Param("id") long id, @Param("now") OffsetDateTime now);

  /**
   * QUEUED -> RUNNING：建立 started_at/deadline_at/last_activity_at 与新 lease；保留 attempt。 CAS：QUEUED
   * 且执行 epoch 匹配。
   */
  @Update(
      """
      update harness_model_invocation
      set status = 'RUNNING',
          attempt = #{attempt},
          worker_token = #{workerToken},
          worker_until = #{leaseUntil},
          started_at = #{startedAt},
          deadline_at = #{deadlineAt},
          last_activity_at = #{lastActivityAt}
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'QUEUED'
        and attempt = #{attempt}
      """)
  int claimFromQueued(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("workerToken") String workerToken,
      @Param("leaseUntil") OffsetDateTime leaseUntil,
      @Param("startedAt") OffsetDateTime startedAt,
      @Param("deadlineAt") OffsetDateTime deadlineAt,
      @Param("lastActivityAt") OffsetDateTime lastActivityAt,
      @Param("attempt") int attempt);

  /**
   * due RETRY_WAIT -> RUNNING：保留首次 started_at/deadline_at；attempt+1；用本次 attempt 开始时刻单调 刷新
   * last_activity_at；CAS 校验 epoch/attempt/next_attempt_at <= now。同时清空旧 attempt 的 safe stream
   * snapshot，避免旧 attempt 的 partial 跨 attempt 泄漏到新 attempt 的 Provider 上下文。
   */
  @Update(
      """
      update harness_model_invocation
      set status = 'RUNNING',
          attempt = attempt + 1,
          worker_token = #{workerToken},
          worker_until = #{leaseUntil},
          last_activity_at = greatest(last_activity_at, #{lastActivityAt}),
          next_attempt_at = null,
          safe_stream_snapshot = null
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RETRY_WAIT'
        and attempt = #{expectedAttempt}
        and next_attempt_at <= #{now}
      """)
  int claimFromRetryWait(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("workerToken") String workerToken,
      @Param("leaseUntil") OffsetDateTime leaseUntil,
      @Param("lastActivityAt") OffsetDateTime lastActivityAt,
      @Param("now") OffsetDateTime now);

  /**
   * 接管已过期 RUNNING lease：替换 worker_token/worker_until；attempt/clocks 保留。CAS 校验 RUNNING + epoch +
   * attempt + token + lease 已过期。
   */
  @Update(
      """
      update harness_model_invocation
      set worker_token = #{workerToken},
          worker_until = #{leaseUntil}
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until <= #{now}
      """)
  int recoverExpiredLease(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("workerToken") String workerToken,
      @Param("leaseUntil") OffsetDateTime leaseUntil,
      @Param("now") OffsetDateTime now);

  /** 续租：单调延长时间，绝不动 last_activity_at/started_at/deadline_at。CAS 校验完整 fence。 */
  @Update(
      """
      update harness_model_invocation
      set worker_until = greatest(worker_until, #{leaseUntil})
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until > #{now}
      """)
  int renewLease(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("leaseUntil") OffsetDateTime leaseUntil,
      @Param("now") OffsetDateTime now);

  /** 记录 Provider delta activity；绝不动 started_at/deadline_at/lease。CAS 校验 lease 有效。 */
  @Update(
      """
      update harness_model_invocation
      set last_activity_at = greatest(last_activity_at, #{activityAt})
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until > #{now}
      """)
  int recordActivity(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("activityAt") OffsetDateTime activityAt,
      @Param("now") OffsetDateTime now);

  /** SUCCEEDED 终态：写 result；清 lease；写 finished_at；recordActivity 同源 lastObserved。 CAS 校验完整 fence。 */
  @Update(
      """
      update harness_model_invocation
      set status = 'SUCCEEDED',
          result = cast(#{resultJson} as jsonb),
          error = null,
          next_attempt_at = null,
          worker_token = null,
          worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          finished_at = greatest(#{finishedAt}, greatest(last_activity_at, #{lastObserved}))
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until > #{now}
      """)
  int completeSuccess(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("resultJson") String resultJson,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("now") OffsetDateTime now);

  /** FAILED 终态：写 error；其余与 SUCCEEDED 同源。 */
  @Update(
      """
      update harness_model_invocation
      set status = 'FAILED',
          error = cast(#{errorJson} as jsonb),
          result = null,
          next_attempt_at = null,
          worker_token = null,
          worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          finished_at = greatest(#{finishedAt}, greatest(last_activity_at, #{lastObserved}))
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until > #{now}
      """)
  int completeFailure(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("errorJson") String errorJson,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("now") OffsetDateTime now);

  /** CANCELLED 终态：无 payload；finished_at 与 lastObserved 取最大值。 */
  @Update(
      """
      update harness_model_invocation
      set status = 'CANCELLED',
          result = null,
          error = null,
          next_attempt_at = null,
          worker_token = null,
          worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          finished_at = greatest(#{finishedAt}, greatest(last_activity_at, #{lastObserved}))
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until > #{now}
      """)
  int completeCancelled(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("now") OffsetDateTime now);

  /** UNKNOWN 终态：写 error；与 FAILED 同源。 */
  @Update(
      """
      update harness_model_invocation
      set status = 'UNKNOWN',
          error = cast(#{errorJson} as jsonb),
          result = null,
          next_attempt_at = null,
          worker_token = null,
          worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          finished_at = greatest(#{finishedAt}, greatest(last_activity_at, #{lastObserved}))
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until > #{now}
      """)
  int completeUnknown(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("errorJson") String errorJson,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("now") OffsetDateTime now);

  /**
   * 转入 RETRY_WAIT：清 lease；保留 started_at/deadline_at；持久化 monotonic 上推 last_activity_at；写
   * next_attempt_at。CAS 校验完整 fence。
   */
  @Update(
      """
      update harness_model_invocation
      set status = 'RETRY_WAIT',
          worker_token = null,
          worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          next_attempt_at = #{nextAttemptAt}
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until > #{now}
      """)
  int scheduleRetry(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
      @Param("now") OffsetDateTime now);

  /** 仅在 RUNNING invocation 上 fenced 写安全流快照；CAS 校验完整 fence。仅修改 last_activity_at 与快照，不影响 lease。 */
  @Update(
      """
      update harness_model_invocation
      set safe_stream_snapshot = cast(#{snapshotJson} as jsonb),
          last_activity_at = greatest(last_activity_at, #{activityAt})
      where id = #{id}
        and thread_id = #{threadId}
        and execution_epoch = #{executionEpoch}
        and status = 'RUNNING'
        and attempt = #{expectedAttempt}
        and worker_token = #{expectedToken}
        and worker_until > #{now}
      """)
  int recordSafeStreamSnapshot(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("expectedAttempt") int expectedAttempt,
      @Param("expectedToken") String expectedToken,
      @Param("snapshotJson") String snapshotJson,
      @Param("activityAt") OffsetDateTime activityAt,
      @Param("now") OffsetDateTime now);
}
