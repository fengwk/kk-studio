package fun.fengwk.kkstudio.core.ai.chat.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 幂等 Chat↔Thread 关联的 MyBatis mapper。 */
@Mapper
public interface ChatThreadMapper extends BaseMapper {

  @Insert(
      """
      insert into chat_thread (chat_id, thread_id)
      values (#{chatId}, #{threadId})
      on conflict (chat_id, thread_id) do nothing
      """)
  int insert(@Param("chatId") long chatId, @Param("threadId") long threadId);

  @Select(
      """
      select thread_id
      from chat_thread
      where chat_id = #{chatId}
      order by created_at desc, thread_id desc
      """)
  List<Long> listThreadIds(@Param("chatId") long chatId);
}
