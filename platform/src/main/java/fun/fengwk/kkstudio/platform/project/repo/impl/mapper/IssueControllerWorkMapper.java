package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueControllerWorkDO;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface IssueControllerWorkMapper extends BaseMapper {

  String COLUMNS = "issue_id, wake_version, due_at, lease_token, lease_until, updated_at";

  @Select("select " + COLUMNS + " from issue_controller_work where issue_id = #{issueId}")
  @Results(
      id = "controllerWorkResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "wake_version", property = "wakeVersion"),
        @Result(column = "due_at", property = "dueAt"),
        @Result(column = "lease_token", property = "leaseToken"),
        @Result(column = "lease_until", property = "leaseUntil"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  IssueControllerWorkDO getById(@Param("issueId") UUID issueId);

  @Select(
      "select " + COLUMNS + " from issue_controller_work where issue_id = #{issueId} for update")
  @ResultMap("controllerWorkResultMap")
  IssueControllerWorkDO lockById(@Param("issueId") UUID issueId);

  @Select(
      """
      insert into issue_controller_work (
          issue_id, wake_version, due_at, lease_token, lease_until, updated_at
      ) values (
          #{issueId}, 1, #{dueAt}, null, null, clock_timestamp()
      )
      on conflict (issue_id) do update
      set due_at = least(issue_controller_work.due_at, excluded.due_at),
          wake_version = issue_controller_work.wake_version + 1,
          updated_at = clock_timestamp()
      returning issue_id, wake_version, due_at, lease_token, lease_until, updated_at
      """)
  @ResultMap("controllerWorkResultMap")
  IssueControllerWorkDO upsertRequest(
      @Param("issueId") UUID issueId, @Param("dueAt") Instant dueAt);

  @Select(
      """
      with candidate as (
          select issue_id
          from issue_controller_work
          where due_at <= #{now}
            and (lease_until is null or lease_until <= #{now})
          order by due_at asc, issue_id asc
          for update skip locked
          limit 1
      )
      update issue_controller_work work
      set lease_token = #{leaseToken},
          lease_until = #{leaseUntil},
          updated_at = clock_timestamp()
      from candidate
      where work.issue_id = candidate.issue_id
      returning work.issue_id, work.wake_version, work.due_at, work.lease_token, work.lease_until, work.updated_at
      """)
  @ResultMap("controllerWorkResultMap")
  IssueControllerWorkDO claimNext(
      @Param("now") Instant now,
      @Param("leaseToken") String leaseToken,
      @Param("leaseUntil") Instant leaseUntil);

  @Update(
      """
      update issue_controller_work
      set lease_until = #{newLeaseUntil},
          updated_at = clock_timestamp()
      where issue_id = #{issueId}
        and lease_token = #{leaseToken}
        and lease_until > #{now}
      """)
  int renewLease(
      @Param("issueId") UUID issueId,
      @Param("leaseToken") String leaseToken,
      @Param("now") Instant now,
      @Param("newLeaseUntil") Instant newLeaseUntil);

  @Delete(
      """
      delete from issue_controller_work
      where issue_id = #{issueId}
        and lease_token = #{leaseToken}
        and lease_until > #{now}
        and wake_version = #{claimedWakeVersion}
      """)
  int deleteIfWakeMatches(
      @Param("issueId") UUID issueId,
      @Param("leaseToken") String leaseToken,
      @Param("claimedWakeVersion") long claimedWakeVersion,
      @Param("now") Instant now);

  @Update(
      """
      update issue_controller_work
      set lease_token = null,
          lease_until = null,
          updated_at = clock_timestamp()
      where issue_id = #{issueId}
        and lease_token = #{leaseToken}
        and lease_until > #{now}
        and wake_version > #{claimedWakeVersion}
      """)
  int clearLeaseIfWakeNewer(
      @Param("issueId") UUID issueId,
      @Param("leaseToken") String leaseToken,
      @Param("claimedWakeVersion") long claimedWakeVersion,
      @Param("now") Instant now);

  @Update(
      """
      update issue_controller_work
      set lease_token = null,
          lease_until = null,
          due_at = case
              when wake_version = #{claimedWakeVersion} then #{requestedAt}
              else least(due_at, #{requestedAt})
          end,
          updated_at = clock_timestamp()
      where issue_id = #{issueId}
        and lease_token = #{leaseToken}
        and lease_until > #{now}
        and wake_version >= #{claimedWakeVersion}
      """)
  int reschedule(
      @Param("issueId") UUID issueId,
      @Param("leaseToken") String leaseToken,
      @Param("claimedWakeVersion") long claimedWakeVersion,
      @Param("now") Instant now,
      @Param("requestedAt") Instant requestedAt);
}
