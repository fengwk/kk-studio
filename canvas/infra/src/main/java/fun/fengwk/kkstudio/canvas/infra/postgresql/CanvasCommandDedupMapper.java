package fun.fengwk.kkstudio.canvas.infra.postgresql;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** {@code canvas_command_dedup} 的原子 SQL 入口。 */
@Mapper
public interface CanvasCommandDedupMapper extends BaseMapper {

  @Insert(
      """
      insert into canvas_command_dedup (canvas_id, idempotency_key, request_hash)
      values (#{canvasId}, #{idempotencyKey}, #{requestHash})
      """)
  int insert(CanvasCommandDedupDO dedup);

  @Select(
      """
      select canvas_id, idempotency_key, request_hash
      from canvas_command_dedup
      where canvas_id = #{canvasId} and idempotency_key = #{idempotencyKey}
      """)
  @Results(
      id = "canvasCommandDedupMap",
      value = {
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "request_hash", property = "requestHash")
      })
  CanvasCommandDedupDO findById(
      @Param("canvasId") UUID canvasId, @Param("idempotencyKey") UUID idempotencyKey);

  @Delete("delete from canvas_command_dedup where canvas_id = #{canvasId}")
  int deleteByCanvas(@Param("canvasId") UUID canvasId);
}
