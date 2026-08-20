package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasCommandDedupDO;

import java.util.UUID;

/** {@code canvas_command_dedup} 的原子 SQL 入口。 */
@Mapper
public interface CanvasCommandDedupMapper extends BaseMapper {

  @Insert(
      """
      insert into canvas_command_dedup (canvas_id, command_id, request_hash)
      values (#{canvasId}, #{commandId}, #{requestHash})
      """)
  int insert(CanvasCommandDedupDO dedup);

  @Select(
      """
      select canvas_id, command_id, request_hash
      from canvas_command_dedup
      where canvas_id = #{canvasId} and command_id = #{commandId}
      """)
  @Results(
      id = "canvasCommandDedupMap",
      value = {
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "command_id", property = "commandId"),
        @Result(column = "request_hash", property = "requestHash")
      })
  CanvasCommandDedupDO findById(
      @Param("canvasId") UUID canvasId, @Param("commandId") UUID commandId);

  @Delete("delete from canvas_command_dedup where canvas_id = #{canvasId}")
  int deleteByCanvas(@Param("canvasId") UUID canvasId);
}
