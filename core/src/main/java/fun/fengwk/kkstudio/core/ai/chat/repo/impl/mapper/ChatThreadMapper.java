package fun.fengwk.kkstudio.core.ai.chat.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for idempotent Chat↔Thread associations. */
@Mapper
public interface ChatThreadMapper extends BaseMapper {

  @Insert(
      """
      insert into chat_thread (chat_id, thread_id)
      values (#{chatId}, #{threadId})
      on conflict (chat_id, thread_id) do nothing
      """)
  int insert(@Param("chatId") long chatId, @Param("threadId") long threadId);
}
