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

/** {@code canvas_session} 归属边的原子 SQL 入口。 */
@Mapper
public interface CanvasSessionMapper extends BaseMapper {

  @Insert("insert into canvas_session (session_id, canvas_id) values (#{sessionId}, #{canvasId})")
  int insert(@Param("sessionId") UUID sessionId, @Param("canvasId") UUID canvasId);

  @Insert(
      "insert into canvas_session (session_id, canvas_id) "
          + "select #{sessionId}, #{canvasId} "
          + "where not exists (select 1 from chat_session where session_id = #{sessionId})")
  int insertIfNotOwnedByOther(@Param("sessionId") UUID sessionId, @Param("canvasId") UUID canvasId);

  @Results(
      id = "canvasSessionMap",
      value = {
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "canvas_id", property = "canvasId")
      })
  @Select("select session_id, canvas_id from canvas_session where session_id = #{sessionId}")
  CanvasSessionDO findBySessionId(@Param("sessionId") UUID sessionId);

  @Select(
      "select session_id from canvas_session where canvas_id = #{canvasId}"
          + " order by created_at desc, session_id desc")
  List<UUID> listSessionIds(@Param("canvasId") UUID canvasId);

  @Delete("delete from canvas_session where session_id = #{sessionId}")
  int deleteBySessionId(@Param("sessionId") UUID sessionId);
}
