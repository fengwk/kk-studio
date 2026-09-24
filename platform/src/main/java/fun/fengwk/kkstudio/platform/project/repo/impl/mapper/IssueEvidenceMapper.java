package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueEvidenceDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_evidence} 的原子 SQL 入口。 */
@Mapper
public interface IssueEvidenceMapper extends BaseMapper {

  String COLUMNS = "issue_id, blob_id, origin, run_id, name, created_at";

  @Insert(
      """
      insert into project_issue_evidence (
          issue_id, blob_id, origin, run_id, name, created_at
      ) values (
          #{issueId}, #{blobId}, #{origin}, #{runId}, #{name}, clock_timestamp()
      )
      on conflict (issue_id, blob_id) do nothing
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  int insertIfAbsent(IssueEvidenceDO evidence);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_evidence where issue_id = #{issueId} and blob_id = #{blobId}")
  @Results(
      id = "issueEvidenceResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "blob_id", property = "blobId"),
        @Result(column = "origin", property = "origin"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "name", property = "name"),
        @Result(column = "created_at", property = "createdAt")
      })
  IssueEvidenceDO findByIssueIdAndBlobId(
      @Param("issueId") UUID issueId, @Param("blobId") UUID blobId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_evidence where issue_id = #{issueId}"
          + " order by created_at desc, blob_id desc limit #{limit}")
  @ResultMap("issueEvidenceResultMap")
  List<IssueEvidenceDO> listRecentByIssueId(
      @Param("issueId") UUID issueId, @Param("limit") int limit);

  @Select("select blob_id from project_issue_evidence where issue_id = #{issueId} order by blob_id")
  List<UUID> listBlobIdsByIssueId(@Param("issueId") UUID issueId);

  @Delete("delete from project_issue_evidence where issue_id = #{issueId}")
  int deleteByIssueId(@Param("issueId") UUID issueId);
}
