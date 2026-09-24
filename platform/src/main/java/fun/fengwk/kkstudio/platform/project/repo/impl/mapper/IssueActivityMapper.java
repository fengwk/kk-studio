package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueActivityDO;

import java.util.List;
import java.util.UUID;

@Mapper
public interface IssueActivityMapper extends BaseMapper {

  String COLUMNS =
      "issue_id, sequence, kind, actor_type, actor_agent_name, "
          + "target_role, run_id, submission_run_id, decision, body, "
          + "idempotency_key, created_at";

  @Select(
      """
      insert into project_issue_activity (
          issue_id, sequence, kind, actor_type, actor_agent_name,
          target_role, run_id, submission_run_id, decision, body,
          idempotency_key, created_at
      )
      select
          #{issueId}::uuid,
          case
            when #{sequence, jdbcType=BIGINT} is not null and #{sequence, jdbcType=BIGINT} > 0
              then #{sequence, jdbcType=BIGINT}
            else (
              select coalesce(max(sequence), 0) + 1
              from project_issue_activity
              where issue_id = #{issueId}::uuid
            )
          end,
          #{kind, jdbcType=VARCHAR},
          #{actorType, jdbcType=VARCHAR},
          #{actorAgentName, jdbcType=VARCHAR},
          #{targetRole, jdbcType=VARCHAR},
          #{runId}::uuid,
          #{submissionRunId}::uuid,
          #{decision, jdbcType=VARCHAR},
          #{body, jdbcType=VARCHAR},
          #{idempotencyKey, jdbcType=VARCHAR},
          clock_timestamp()
      returning issue_id, sequence, kind, actor_type, actor_agent_name,
                target_role, run_id, submission_run_id, decision, body,
                idempotency_key, created_at
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  @Results(
      id = "issueActivityResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "kind", property = "kind"),
        @Result(column = "actor_type", property = "actorType"),
        @Result(column = "actor_agent_name", property = "actorAgentName"),
        @Result(column = "target_role", property = "targetRole"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "submission_run_id", property = "submissionRunId"),
        @Result(column = "decision", property = "decision"),
        @Result(column = "body", property = "body"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "created_at", property = "createdAt")
      })
  IssueActivityDO insert(IssueActivityDO activity);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_activity where issue_id = #{issueId} and idempotency_key = #{idempotencyKey}")
  @ResultMap("issueActivityResultMap")
  IssueActivityDO findByIssueIdAndIdempotencyKey(
      @Param("issueId") UUID issueId, @Param("idempotencyKey") String idempotencyKey);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_activity where issue_id = #{issueId} order by sequence asc")
  @ResultMap("issueActivityResultMap")
  List<IssueActivityDO> listByIssueId(@Param("issueId") UUID issueId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_activity where issue_id = #{issueId} and sequence > #{afterSequence}"
          + " order by sequence asc limit #{limit}")
  @ResultMap("issueActivityResultMap")
  List<IssueActivityDO> listPage(
      @Param("issueId") UUID issueId,
      @Param("afterSequence") long afterSequence,
      @Param("limit") int limit);

  @Select(
      "select exists (select 1 from project_issue_activity where issue_id = #{issueId} and kind = #{kind})")
  boolean existsByIssueIdAndKind(@Param("issueId") UUID issueId, @Param("kind") String kind);

  @Select(
      "select exists (select 1 from project_issue_activity where issue_id = #{issueId}"
          + " and sequence > #{afterSequence} and kind = #{kind})")
  boolean existsAfterSequenceAndKind(
      @Param("issueId") UUID issueId,
      @Param("afterSequence") long afterSequence,
      @Param("kind") String kind);

  @Select(
      "select coalesce(max(sequence), 0) from project_issue_activity where issue_id = #{issueId} and kind in ('RECOVERY', 'SPEC_CHANGE')")
  long findReviewWindowStartSequence(@Param("issueId") UUID issueId);

  @Select(
      """
      select count(distinct submission_run_id)
      from project_issue_activity
      where issue_id = #{issueId}
        and sequence > #{afterSequence}
        and kind = 'REVIEW_DECISION'
        and decision = 'REQUEST_CHANGES'
        and submission_run_id is not null
      """)
  long countRejectionsSince(
      @Param("issueId") UUID issueId, @Param("afterSequence") long afterSequence);

  @Select(
      """
      select count(distinct submission_run_id)
      from project_issue_activity
      where issue_id = #{issueId}
        and sequence > #{afterSequence}
        and actor_type = #{actorType}
        and kind = 'REVIEW_DECISION'
        and decision = 'REQUEST_CHANGES'
        and submission_run_id is not null
      """)
  long countRejectionsSinceWithActorType(
      @Param("issueId") UUID issueId,
      @Param("afterSequence") long afterSequence,
      @Param("actorType") String actorType);

  @Delete("delete from project_issue_activity where issue_id = #{issueId}")
  int deleteByIssueId(@Param("issueId") UUID issueId);
}
