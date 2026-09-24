package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueDO;

import java.util.List;
import java.util.UUID;

@Mapper
public interface IssueMapper extends BaseMapper {

  String COLUMNS =
      "id, project_id, number, title, description, status, "
          + "assignee_agent_name, reviewer_agent_name, version, "
          + "archived_at, created_at, updated_at";

  @Insert(
      """
      insert into project_issue (
          id, project_id, number, title, description, status,
          assignee_agent_name, reviewer_agent_name, version,
          archived_at, created_at, updated_at
      ) values (
          #{id}, #{projectId}, #{number}, #{title}, #{description}, #{status},
          #{assigneeAgentName}, #{reviewerAgentName}, 0,
          null, clock_timestamp(), clock_timestamp()
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
        @Result(column = "status", property = "status"),
        @Result(column = "assignee_agent_name", property = "assigneeAgentName"),
        @Result(column = "reviewer_agent_name", property = "reviewerAgentName"),
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
          + " from project_issue where project_id = #{projectId} and number = #{number}")
  @ResultMap("issueResultMap")
  IssueDO getByProjectAndNumber(@Param("projectId") UUID projectId, @Param("number") long number);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue where project_id = #{projectId} order by number asc")
  @ResultMap("issueResultMap")
  List<IssueDO> listByProjectId(@Param("projectId") UUID projectId);

  @Select(
      """
      select id, project_id, number, title, description, status,
             assignee_agent_name, reviewer_agent_name, version,
             archived_at, created_at, updated_at
      from project_issue
      where project_id = #{projectId}
        and (archived_at is not null) = #{archived}
      order by number asc
      """)
  @ResultMap("issueResultMap")
  List<IssueDO> listByProjectIdAndArchived(
      @Param("projectId") UUID projectId, @Param("archived") boolean archived);

  @Update(
      """
      update project_issue
      set title = #{issue.title},
          description = #{issue.description},
          status = #{issue.status},
          assignee_agent_name = #{issue.assigneeAgentName},
          reviewer_agent_name = #{issue.reviewerAgentName},
          archived_at = #{issue.archivedAt},
          updated_at = clock_timestamp(),
          version = version + 1
      where id = #{issue.id} and version = #{expectedVersion}
      """)
  int updateById(@Param("issue") IssueDO issue, @Param("expectedVersion") long expectedVersion);

  @Delete("delete from project_issue where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
