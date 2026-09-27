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
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue} 表 SQL 入口。 */
@Mapper
public interface IssueMapper extends BaseMapper {

  String COLUMNS =
      "id, project_id, number, title, description, state, blocked_from_state, block_reason,"
          + " pause_reason, pause_detail, next_run_ordinal, next_activity_sequence, version,"
          + " archived_at, created_at, updated_at";

  @Insert(
      """
      insert into project_issue (
          id, project_id, number, title, description, state, blocked_from_state, block_reason,
          pause_reason, pause_detail, next_run_ordinal, next_activity_sequence, version,
          archived_at, created_at, updated_at
      ) values (
          #{id}, #{projectId}, #{number}, #{title}, #{description}, #{state}, null, null,
          null, null, 1, 1, 0, null, clock_timestamp(), clock_timestamp()
      )
      """)
  int insert(IssueDO issue);

  @Select("select " + COLUMNS + " from project_issue where id = #{id}")
  @Results(
      id = "issueResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "project_id", property = "projectId"),
        @Result(column = "number", property = "number"),
        @Result(column = "title", property = "title"),
        @Result(column = "description", property = "description"),
        @Result(column = "state", property = "state"),
        @Result(column = "blocked_from_state", property = "blockedFromState"),
        @Result(column = "block_reason", property = "blockReason"),
        @Result(column = "pause_reason", property = "pauseReason"),
        @Result(column = "pause_detail", property = "pauseDetail"),
        @Result(column = "next_run_ordinal", property = "nextRunOrdinal"),
        @Result(column = "next_activity_sequence", property = "nextActivitySequence"),
        @Result(column = "version", property = "version"),
        @Result(column = "archived_at", property = "archivedAt"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  IssueDO getById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from project_issue where id = #{id} for update")
  @ResultMap("issueResultMap")
  IssueDO lockById(@Param("id") UUID id);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue where project_id = #{projectId}"
          + " and number = #{number}")
  @ResultMap("issueResultMap")
  IssueDO getByProjectAndNumber(@Param("projectId") UUID projectId, @Param("number") long number);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue where project_id = #{projectId}"
          + " order by number asc")
  @ResultMap("issueResultMap")
  List<IssueDO> listByProjectId(@Param("projectId") UUID projectId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue where project_id = #{projectId}"
          + " and (archived_at is not null) = #{archived} order by number asc")
  @ResultMap("issueResultMap")
  List<IssueDO> listByProjectIdAndArchived(
      @Param("projectId") UUID projectId, @Param("archived") boolean archived);

  @Update(
      """
      update project_issue
      set title = #{issue.title},
          description = #{issue.description},
          state = #{issue.state},
          blocked_from_state = #{issue.blockedFromState},
          block_reason = #{issue.blockReason},
          pause_reason = #{issue.pauseReason},
          pause_detail = #{issue.pauseDetail},
          next_run_ordinal = #{issue.nextRunOrdinal},
          next_activity_sequence = #{issue.nextActivitySequence},
          archived_at = #{issue.archivedAt},
          updated_at = clock_timestamp(),
          version = version + 1
      where id = #{issue.id} and version = #{expectedVersion}
      """)
  int updateById(@Param("issue") IssueDO issue, @Param("expectedVersion") long expectedVersion);

  @Delete("delete from project_issue where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
