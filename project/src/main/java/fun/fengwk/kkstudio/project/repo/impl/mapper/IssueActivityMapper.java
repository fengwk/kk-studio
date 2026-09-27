package fun.fengwk.kkstudio.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueActivityDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_activity} 表 SQL 入口。 */
@Mapper
public interface IssueActivityMapper extends BaseMapper {

  String COLUMNS =
      "issue_id, sequence, kind, actor_type, actor_agent_name, run_id, body, data,"
          + " idempotency_key, request_hash, created_at";

  @Insert(
      """
      insert into project_issue_activity (
          issue_id, sequence, kind, actor_type, actor_agent_name, run_id, body, data,
          idempotency_key, request_hash, created_at
      ) values (
          #{issueId}, #{sequence}, #{kind}, #{actorType}, #{actorAgentName}, #{runId}, #{body},
          #{data}::jsonb, #{idempotencyKey}, #{requestHash}, clock_timestamp()
      )
      """)
  int insert(IssueActivityDO activity);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_activity where issue_id = #{issueId}"
          + " order by sequence asc")
  @Results(
      id = "issueActivityResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "kind", property = "kind"),
        @Result(column = "actor_type", property = "actorType"),
        @Result(column = "actor_agent_name", property = "actorAgentName"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "body", property = "body"),
        @Result(column = "data", property = "data"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "request_hash", property = "requestHash"),
        @Result(column = "created_at", property = "createdAt")
      })
  List<IssueActivityDO> listByIssueId(@Param("issueId") UUID issueId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_activity where issue_id = #{issueId}"
          + " and idempotency_key = #{idempotencyKey}")
  @ResultMap("issueActivityResultMap")
  IssueActivityDO findByIdempotencyKey(
      @Param("issueId") UUID issueId, @Param("idempotencyKey") String idempotencyKey);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_activity where issue_id = #{issueId}"
          + " and sequence > #{afterSequence} order by sequence asc limit #{limit}")
  @ResultMap("issueActivityResultMap")
  List<IssueActivityDO> listPage(
      @Param("issueId") UUID issueId,
      @Param("afterSequence") long afterSequence,
      @Param("limit") int limit);

  @Select("select count(*) from project_issue_activity where issue_id = #{issueId}")
  long countByIssueId(@Param("issueId") UUID issueId);

  @Delete("delete from project_issue_activity where issue_id = #{issueId}")
  int deleteByIssueId(@Param("issueId") UUID issueId);
}
