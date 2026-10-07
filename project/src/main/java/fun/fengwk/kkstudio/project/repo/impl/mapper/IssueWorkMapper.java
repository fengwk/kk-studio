package fun.fengwk.kkstudio.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueWorkDO;

import java.time.Duration;
import java.util.UUID;

/**
 * {@code project_issue_work} 表 SQL 入口：确定性 wake/lease 围栏。
 *
 * <p>调度与租约统一使用 statement_timestamp，截断到毫秒，避免 timestamp(3) 舍入把立即到期写到未来。
 */
@Mapper
public interface IssueWorkMapper extends BaseMapper {

  String COLUMNS =
      "issue_id, wake_version, due_at, lease_token, lease_until, created_at, updated_at";

  @Select("select " + COLUMNS + " from project_issue_work where issue_id = #{issueId}")
  @Results(
      id = "issueWorkResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "wake_version", property = "wakeVersion"),
        @Result(column = "due_at", property = "dueAt"),
        @Result(column = "lease_token", property = "leaseToken"),
        @Result(column = "lease_until", property = "leaseUntil"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  IssueWorkDO getById(@Param("issueId") UUID issueId);

  @Select("select " + COLUMNS + " from project_issue_work where issue_id = #{issueId} for update")
  @ResultMap("issueWorkResultMap")
  IssueWorkDO lockById(@Param("issueId") UUID issueId);

  @Select(
      value =
          """
      <script>
      <bind name="delayMillis" value="delay.toMillis()" />
      insert into project_issue_work (
          issue_id, wake_version, due_at, lease_token, lease_until, created_at, updated_at
      ) values (
          #{issueId}, 1, date_trunc('milliseconds', statement_timestamp()) + #{delayMillis} * interval '1 millisecond',
          null, null, date_trunc('milliseconds', statement_timestamp()), date_trunc('milliseconds', statement_timestamp())
      )
      on conflict (issue_id) do update
      set due_at = least(project_issue_work.due_at, excluded.due_at),
          wake_version = project_issue_work.wake_version + 1,
          updated_at = date_trunc('milliseconds', statement_timestamp())
      returning issue_id, wake_version, due_at, lease_token, lease_until, created_at, updated_at
      </script>
      """,
      affectData = true)
  @ResultMap("issueWorkResultMap")
  @Options(flushCache = FlushCachePolicy.TRUE, useCache = false)
  IssueWorkDO upsertRequest(@Param("issueId") UUID issueId, @Param("delay") Duration delay);

  @Select(
      value =
          """
      <script>
      <bind name="leaseMillis" value="leaseDuration.toMillis()" />
      with candidate as (
          select issue_id
          from project_issue_work
          where due_at &lt;= date_trunc('milliseconds', statement_timestamp())
            and (lease_until is null or lease_until &lt;= date_trunc('milliseconds', statement_timestamp()))
          order by due_at asc, issue_id asc
          for update skip locked
          limit 1
      )
      update project_issue_work work
      set lease_token = #{leaseToken},
          lease_until = date_trunc('milliseconds', statement_timestamp()) + #{leaseMillis} * interval '1 millisecond',
          updated_at = date_trunc('milliseconds', statement_timestamp())
      from candidate
      where work.issue_id = candidate.issue_id
      returning work.issue_id, work.wake_version, work.due_at, work.lease_token,
                work.lease_until, work.created_at, work.updated_at
      </script>
      """,
      affectData = true)
  @ResultMap("issueWorkResultMap")
  @Options(flushCache = FlushCachePolicy.TRUE, useCache = false)
  IssueWorkDO claimNext(
      @Param("leaseToken") String leaseToken, @Param("leaseDuration") Duration leaseDuration);

  @Update(
      """
      <script>
      <bind name="leaseMillis" value="leaseDuration.toMillis()" />
      update project_issue_work
      set lease_until = greatest(lease_until, date_trunc('milliseconds', statement_timestamp()) + #{leaseMillis} * interval '1 millisecond'),
          updated_at = date_trunc('milliseconds', statement_timestamp())
      where issue_id = #{issueId}
        and lease_token = #{leaseToken}
        and lease_until > date_trunc('milliseconds', statement_timestamp())
      </script>
      """)
  int renewLease(
      @Param("issueId") UUID issueId,
      @Param("leaseToken") String leaseToken,
      @Param("leaseDuration") Duration leaseDuration);

  /**
   * 单条 CTE 内完成或释放：版本与租约匹配则删除，版本被新 wake 推进则释放租约并把 due 提前到当前时刻，围栏不匹配则不写。返回实际发生的写入结果，使
   * 调用方能在同一事务内区分「released 成功」与「完全未写」。
   */
  @Select(
      value =
          """
      with deleted as (
          delete from project_issue_work
          where issue_id = #{issueId}
            and lease_token = #{leaseToken}
            and lease_until > date_trunc('milliseconds', statement_timestamp())
            and wake_version = #{claimedWakeVersion}
          returning issue_id
      ), released as (
          update project_issue_work
          set lease_token = null, lease_until = null,
              due_at = least(due_at, date_trunc('milliseconds', statement_timestamp())),
              updated_at = date_trunc('milliseconds', statement_timestamp())
          where issue_id = #{issueId}
            and lease_token = #{leaseToken}
            and lease_until > date_trunc('milliseconds', statement_timestamp())
            and wake_version != #{claimedWakeVersion}
          returning issue_id
      )
      select case
          when exists(select 1 from deleted) then 'DELETED'
          when exists(select 1 from released) then 'RELEASED'
          else 'NONE'
      end
      """,
      affectData = true)
  @Options(flushCache = FlushCachePolicy.TRUE, useCache = false)
  IssueWorkCompletion completeWork(
      @Param("issueId") UUID issueId,
      @Param("leaseToken") String leaseToken,
      @Param("claimedWakeVersion") long claimedWakeVersion);

  @Update(
      """
      <script>
      <bind name="delayMillis" value="delay.toMillis()" />
      update project_issue_work
      set lease_token = null,
          lease_until = null,
          due_at = case when wake_version = #{claimedWakeVersion}
              then date_trunc('milliseconds', statement_timestamp()) + #{delayMillis} * interval '1 millisecond'
              else least(due_at, date_trunc('milliseconds', statement_timestamp()) + #{delayMillis} * interval '1 millisecond') end,
          updated_at = date_trunc('milliseconds', statement_timestamp())
      where issue_id = #{issueId}
        and lease_token = #{leaseToken}
        and lease_until > date_trunc('milliseconds', statement_timestamp())
      </script>
      """)
  int rescheduleWork(
      @Param("issueId") UUID issueId,
      @Param("leaseToken") String leaseToken,
      @Param("claimedWakeVersion") long claimedWakeVersion,
      @Param("delay") Duration delay);

  @Delete("delete from project_issue_work where issue_id = #{issueId}")
  int deleteByIssueId(@Param("issueId") UUID issueId);
}
