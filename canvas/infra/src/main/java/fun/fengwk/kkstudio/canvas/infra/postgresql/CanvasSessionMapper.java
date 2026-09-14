package fun.fengwk.kkstudio.canvas.infra.postgresql;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** {@code session_owner.canvas_id} 归属边的 SQL 入口。 */
@Mapper
public interface CanvasSessionMapper extends BaseMapper {

  @Insert("insert into session_owner (session_id, canvas_id) values (#{sessionId}, #{canvasId})")
  int insert(@Param("sessionId") UUID sessionId, @Param("canvasId") UUID canvasId);

  @Results(
      id = "canvasSessionMap",
      value = {
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "canvas_id", property = "canvasId")
      })
  @Select(
      "select session_id, canvas_id from session_owner"
          + " where session_id = #{sessionId} and canvas_id is not null")
  CanvasSessionDO findBySessionId(@Param("sessionId") UUID sessionId);

  @Select(
      "select session_id from session_owner where canvas_id = #{canvasId}"
          + " order by created_at desc, session_id desc")
  List<UUID> listSessionIds(@Param("canvasId") UUID canvasId);

  @Delete("delete from session_owner where session_id = #{sessionId} and canvas_id is not null")
  int deleteBySessionId(@Param("sessionId") UUID sessionId);
}
