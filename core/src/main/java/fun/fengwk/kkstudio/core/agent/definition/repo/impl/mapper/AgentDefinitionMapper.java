package fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper;

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

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;

import java.util.List;

@Mapper
public interface AgentDefinitionMapper extends BaseMapper {

  String COLUMNS =
      "id, name, description, system_prompt, model_id, variant, config, version, "
          + "created_at as create_time, updated_at as update_time";

  @Select("select count(*) from agent_definition")
  long count();

  @Select(
      "select "
          + COLUMNS
          + " from agent_definition order by id asc limit #{limit} offset #{offset}")
  @Results(
      id = "agentDefinitionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "system_prompt", property = "systemPrompt"),
        @Result(column = "model_id", property = "modelId"),
        @Result(column = "variant", property = "variant"),
        @Result(column = "config", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentDefinitionDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select("select " + COLUMNS + " from agent_definition where id = #{id}")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getById(@Param("id") long id);

  @Select("select " + COLUMNS + " from agent_definition where name = #{name}")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getByName(@Param("name") String name);

  @Insert(
      """
      insert into agent_definition (
          id, name, description, system_prompt, model_id, variant, config,
          created_at, updated_at, version
      ) values (
          #{id}, #{name}, #{description}, #{systemPrompt}, #{modelId}, #{variant},
          cast(#{configJson} as jsonb), current_timestamp, current_timestamp, 0
      )
      """)
  int insert(AgentDefinitionDO agent);

  @Update(
      """
      update agent_definition
      set name = #{agent.name}, description = #{agent.description},
          system_prompt = #{agent.systemPrompt}, model_id = #{agent.modelId},
          variant = #{agent.variant}, config = cast(#{agent.configJson} as jsonb),
          updated_at = current_timestamp, version = version + 1
      where id = #{agent.id}
      """)
  int updateById(@Param("agent") AgentDefinitionDO agent);

  @Delete("delete from agent_definition where id = #{id}")
  int deleteById(@Param("id") long id);
}
