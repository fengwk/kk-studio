package fun.fengwk.kkstudio.core.ai.runtime.goal.store.mapper;

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

import fun.fengwk.kkstudio.core.ai.runtime.goal.store.model.HarnessThreadGoalDO;

import java.time.OffsetDateTime;

@Mapper
public interface HarnessThreadGoalMapper extends BaseMapper {
  String COLUMNS =
      "thread_id, objective, token_budget, status, reason,"
          + " created_at as create_time, updated_at as update_time";

  @Insert(
      "insert into harness_thread_goal (thread_id, objective, token_budget, status, reason,"
          + " created_at, updated_at) values (#{threadId}, #{objective}, #{tokenBudget},"
          + " #{status}, #{reason}, #{createTime}, #{updateTime})")
  int insert(HarnessThreadGoalDO goal);

  @Select("select " + COLUMNS + " from harness_thread_goal where thread_id = #{threadId}")
  @Results(
      id = "harnessThreadGoalResultMap",
      value = {
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "objective", property = "objective"),
        @Result(column = "token_budget", property = "tokenBudget"),
        @Result(column = "status", property = "status"),
        @Result(column = "reason", property = "reason"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  HarnessThreadGoalDO find(@Param("threadId") long threadId);

  @Select(
      "select " + COLUMNS + " from harness_thread_goal where thread_id = #{threadId} for update")
  @ResultMap("harnessThreadGoalResultMap")
  HarnessThreadGoalDO findForUpdate(@Param("threadId") long threadId);

  @Update(
      "update harness_thread_goal set objective = #{objective}, token_budget = #{tokenBudget},"
          + " status = #{status}, reason = #{reason}, updated_at = #{updateTime}"
          + " where thread_id = #{threadId}")
  int update(HarnessThreadGoalDO goal);

  @Update(
      "update harness_thread_goal set status = #{status}, reason = #{reason},"
          + " updated_at = #{now} where thread_id = #{threadId} and status = 'active'")
  int updateTerminal(
      @Param("threadId") long threadId,
      @Param("status") String status,
      @Param("reason") String reason,
      @Param("now") OffsetDateTime now);

  @Delete("delete from harness_thread_goal where thread_id = #{threadId}")
  int delete(@Param("threadId") long threadId);
}
