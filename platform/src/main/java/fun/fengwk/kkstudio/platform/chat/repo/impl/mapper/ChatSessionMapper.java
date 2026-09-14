package fun.fengwk.kkstudio.platform.chat.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.platform.chat.repo.impl.model.ChatSessionDO;

import java.util.List;
import java.util.UUID;

/** {@code session_owner.chat_id} 归属边的 SQL 入口。 */
@Mapper
public interface ChatSessionMapper extends BaseMapper {

  @Insert("insert into session_owner (session_id, chat_id) values (#{sessionId}, #{chatId})")
  int insert(@Param("sessionId") UUID sessionId, @Param("chatId") UUID chatId);

  @Results(
      id = "chatSessionMap",
      value = {
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "chat_id", property = "chatId")
      })
  @Select(
      "select session_id, chat_id from session_owner"
          + " where session_id = #{sessionId} and chat_id is not null")
  ChatSessionDO findBySessionId(@Param("sessionId") UUID sessionId);

  @Select(
      "select session_id from session_owner where chat_id = #{chatId}"
          + " order by created_at desc, session_id desc")
  List<UUID> listSessionIds(@Param("chatId") UUID chatId);

  @Delete("delete from session_owner where session_id = #{sessionId} and chat_id is not null")
  int deleteBySessionId(@Param("sessionId") UUID sessionId);
}
