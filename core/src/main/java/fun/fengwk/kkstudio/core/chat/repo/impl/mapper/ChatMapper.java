package fun.fengwk.kkstudio.core.chat.repo.impl.mapper;

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

import fun.fengwk.kkstudio.core.chat.repo.impl.model.ChatDO;

import java.util.List;

@Mapper
public interface ChatMapper extends BaseMapper {

  String COLUMNS =
      "id, title, default_agent_id, version, "
          + "created_at as create_time, updated_at as update_time";

  @Select("select " + COLUMNS + " from chat order by updated_at desc, id desc")
  @Results(
      id = "chatResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "default_agent_id", property = "defaultAgentId"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<ChatDO> listNewestFirst();

  @Select("select " + COLUMNS + " from chat where id = #{id}")
  @ResultMap("chatResultMap")
  ChatDO getById(@Param("id") long id);

  @Insert(
      """
      insert into chat (
          id, title, default_agent_id,
          created_at, updated_at, version
      ) values (
          #{id}, #{title}, #{defaultAgentId},
          current_timestamp, current_timestamp, 0
      )
      """)
  int insert(ChatDO chat);

  @Update(
      """
      update chat
      set title = #{chat.title}, default_agent_id = #{chat.defaultAgentId},
          updated_at = current_timestamp, version = version + 1
      where id = #{chat.id} and version = #{expectedVersion}
      """)
  int updateById(@Param("chat") ChatDO chat, @Param("expectedVersion") long expectedVersion);

  @Delete("delete from chat where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") long id, @Param("expectedVersion") long expectedVersion);
}
