package fun.fengwk.kkstudio.platform.ai.chat.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.platform.ai.chat.repo.impl.model.ChatSessionDO;

import java.util.List;
import java.util.UUID;

/** {@code chat_session} 归属边的原子 SQL 入口。 */
@Mapper
public interface ChatSessionMapper extends BaseMapper {

  @Insert("insert into chat_session (session_id, chat_id) values (#{sessionId}, #{chatId})")
  int insert(@Param("sessionId") UUID sessionId, @Param("chatId") UUID chatId);

  @Insert(
      "insert into chat_session (session_id, chat_id) "
          + "select #{sessionId}, #{chatId} "
          + "where not exists (select 1 from canvas_session where session_id = #{sessionId})")
  int insertIfNotOwnedByOther(@Param("sessionId") UUID sessionId, @Param("chatId") UUID chatId);

  @Results(
      id = "chatSessionMap",
      value = {
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "chat_id", property = "chatId")
      })
  @Select("select session_id, chat_id from chat_session where session_id = #{sessionId}")
  ChatSessionDO findBySessionId(@Param("sessionId") UUID sessionId);

  @Select(
      "select session_id from chat_session where chat_id = #{chatId}"
          + " order by created_at desc, session_id desc")
  List<UUID> listSessionIds(@Param("chatId") UUID chatId);

  @Delete("delete from chat_session where session_id = #{sessionId}")
  int deleteBySessionId(@Param("sessionId") UUID sessionId);
}
