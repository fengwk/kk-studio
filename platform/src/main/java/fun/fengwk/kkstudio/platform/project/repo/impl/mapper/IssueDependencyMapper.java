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

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueDependencyDO;

import java.util.List;
import java.util.UUID;

@Mapper
public interface IssueDependencyMapper extends BaseMapper {

  @Insert(
      """
      insert into issue_dependency (issue_id, depends_on_issue_id, project_id, created_at)
      values (#{issueId}, #{dependsOnIssueId}, #{projectId}, clock_timestamp())
      """)
  int insert(IssueDependencyDO dependency);

  @Delete(
      """
      delete from issue_dependency
      where issue_id = #{issueId} and depends_on_issue_id = #{dependsOnIssueId}
      """)
  int delete(@Param("issueId") UUID issueId, @Param("dependsOnIssueId") UUID dependsOnIssueId);

  @Select(
      """
      select issue_id, depends_on_issue_id, project_id, created_at
      from issue_dependency
      where issue_id = #{issueId}
      order by created_at asc
      """)
  @Results(
      id = "issueDependencyResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "depends_on_issue_id", property = "dependsOnIssueId"),
        @Result(column = "project_id", property = "projectId"),
        @Result(column = "created_at", property = "createdAt")
      })
  List<IssueDependencyDO> listByIssueId(@Param("issueId") UUID issueId);

  @Select(
      """
      select issue_id, depends_on_issue_id, project_id, created_at
      from issue_dependency
      where depends_on_issue_id = #{dependsOnIssueId}
      order by created_at asc
      """)
  @ResultMap("issueDependencyResultMap")
  List<IssueDependencyDO> listByDependsOnIssueId(@Param("dependsOnIssueId") UUID dependsOnIssueId);

  @Select(
      """
      select issue_id, depends_on_issue_id, project_id, created_at
      from issue_dependency
      where project_id = #{projectId}
      order by created_at asc
      """)
  @ResultMap("issueDependencyResultMap")
  List<IssueDependencyDO> listByProjectId(@Param("projectId") UUID projectId);

  /**
   * 使用 recursive CTE 检测从 fromIssueId 是否能沿 depends_on_issue 路径到达 toIssueId。 若返回 true，则表示两者间已存在有向路径。
   */
  @Select(
      """
      with recursive reach as (
          select depends_on_issue_id as target_id
          from issue_dependency
          where issue_id = #{fromIssueId}
          union
          select d.depends_on_issue_id
          from issue_dependency d
          inner join reach r on d.issue_id = r.target_id
      )
      select exists (
          select 1 from reach where target_id = #{toIssueId}
      )
      """)
  boolean checkHasPath(@Param("fromIssueId") UUID fromIssueId, @Param("toIssueId") UUID toIssueId);
}
