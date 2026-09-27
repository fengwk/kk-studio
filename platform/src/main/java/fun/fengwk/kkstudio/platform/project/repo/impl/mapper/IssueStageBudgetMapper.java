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

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueStageBudgetDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_stage_budget} 表 SQL 入口。 */
@Mapper
public interface IssueStageBudgetMapper extends BaseMapper {

  String COLUMNS = "issue_id, state, max_runs, budget_after_ordinal";

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_stage_budget"
          + " where issue_id = #{issueId} and state = #{state}")
  @Results(
      id = "issueStageBudgetResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "state", property = "state"),
        @Result(column = "max_runs", property = "maxRuns"),
        @Result(column = "budget_after_ordinal", property = "budgetAfterOrdinal")
      })
  IssueStageBudgetDO get(@Param("issueId") UUID issueId, @Param("state") String state);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_stage_budget"
          + " where issue_id = #{issueId} order by state asc")
  @ResultMap("issueStageBudgetResultMap")
  List<IssueStageBudgetDO> listByIssueId(@Param("issueId") UUID issueId);

  @Insert(
      """
      insert into project_issue_stage_budget (
          issue_id, state, max_runs, budget_after_ordinal, created_at, updated_at
      ) values (
          #{issueId}, #{state}, #{maxRuns}, #{budgetAfterOrdinal}, clock_timestamp(), clock_timestamp()
      )
      """)
  int insert(IssueStageBudgetDO budget);

  @Update(
      """
      update project_issue_stage_budget
      set max_runs = #{maxRuns},
          budget_after_ordinal = #{budgetAfterOrdinal},
          updated_at = clock_timestamp()
      where issue_id = #{issueId} and state = #{state}
      """)
  int update(IssueStageBudgetDO budget);

  @Delete("delete from project_issue_stage_budget where issue_id = #{issueId} and state = #{state}")
  int delete(@Param("issueId") UUID issueId, @Param("state") String state);
}
