package fun.fengwk.kkstudio.core.chat.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.chat.repo.impl.model.ChatSessionDO;

import java.util.List;

@Mapper
public interface ChatSessionMapper extends BaseMapper {

  @Insert(
      """
      insert into chat_session (
          id, chat_id, session_id, gmt_create
      ) values (
          #{id}, #{chatId}, #{sessionId}, current_timestamp(3)
      )
      """)
  int insert(ChatSessionDO membership);

  @Select(
      """
      select id, chat_id, session_id, gmt_create as create_time
      from chat_session
      where chat_id = #{chatId} and session_id = #{sessionId}
      """)
  @Results(
      id = "chatSessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "chat_id", property = "chatId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "create_time", property = "createTime")
      })
  ChatSessionDO findByChatAndSession(
      @Param("chatId") long chatId, @Param("sessionId") long sessionId);

  @Select(
      """
      select session_id
      from chat_session
      where chat_id = #{chatId}
      order by gmt_create asc, id asc
      """)
  List<Long> listSessionIdsByChatId(@Param("chatId") long chatId);

  @Delete("delete from chat_session where chat_id = #{chatId} and session_id = #{sessionId}")
  int deleteByChatAndSession(@Param("chatId") long chatId, @Param("sessionId") long sessionId);

  @Delete("delete from chat_session where chat_id = #{chatId}")
  int deleteByChatId(@Param("chatId") long chatId);
}
