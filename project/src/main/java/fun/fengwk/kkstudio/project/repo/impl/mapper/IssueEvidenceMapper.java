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

import fun.fengwk.kkstudio.project.repo.impl.model.IssueEvidenceDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_evidence} 表 SQL 入口。 */
@Mapper
public interface IssueEvidenceMapper extends BaseMapper {

  String COLUMNS = "issue_id, blob_id, actor_agent_name, run_id, name, created_at";

  @Insert(
      """
      insert into project_issue_evidence (
          issue_id, blob_id, actor_agent_name, run_id, name, created_at
      ) values (
          #{issueId}, #{blobId}, #{actorAgentName}, #{runId}, #{name}, clock_timestamp()
      ) on conflict (issue_id, blob_id) do nothing
      """)
  int insert(IssueEvidenceDO evidence);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_evidence where issue_id = #{issueId} and blob_id = #{blobId}")
  @Results(
      id = "issueEvidenceResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "blob_id", property = "blobId"),
        @Result(column = "actor_agent_name", property = "actorAgentName"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "name", property = "name"),
        @Result(column = "created_at", property = "createdAt")
      })
  IssueEvidenceDO get(@Param("issueId") UUID issueId, @Param("blobId") UUID blobId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_evidence where issue_id = #{issueId}"
          + " order by created_at desc, blob_id asc")
  @ResultMap("issueEvidenceResultMap")
  List<IssueEvidenceDO> listByIssueId(@Param("issueId") UUID issueId);

  @Delete("delete from project_issue_evidence where issue_id = #{issueId}")
  int deleteByIssueId(@Param("issueId") UUID issueId);
}
