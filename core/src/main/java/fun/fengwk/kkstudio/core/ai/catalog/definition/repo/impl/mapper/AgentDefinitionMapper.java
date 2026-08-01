package fun.fengwk.kkstudio.core.ai.catalog.definition.repo.impl.mapper;

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

import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.impl.model.AgentDefinitionDO;

import java.util.List;

@Mapper
public interface AgentDefinitionMapper extends BaseMapper {

  String COLUMNS =
      "name, description, system_prompt, model_provider_name, model_name, variant, config, version, "
          + "created_at as create_time, updated_at as update_time";

  @Select("select count(*) from agent_definition")
  long count();

  @Select(
      "select "
          + COLUMNS
          + " from agent_definition order by name asc limit #{limit} offset #{offset}")
  @Results(
      id = "agentDefinitionResultMap",
      value = {
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "system_prompt", property = "systemPrompt"),
        @Result(column = "model_provider_name", property = "modelProviderName"),
        @Result(column = "model_name", property = "modelName"),
        @Result(column = "variant", property = "variant"),
        @Result(column = "config", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentDefinitionDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select("select " + COLUMNS + " from agent_definition where name = #{name}")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getByName(@Param("name") String name);

  @Insert(
      """
      insert into agent_definition (
          name, description, system_prompt, model_provider_name, model_name, variant, config,
          created_at, updated_at, version
      ) values (
          #{name}, #{description}, #{systemPrompt}, #{modelProviderName}, #{modelName}, #{variant},
          cast(#{configJson} as jsonb), current_timestamp, current_timestamp, 0
      )
      """)
  int insert(AgentDefinitionDO agent);

  @Update(
      """
      update agent_definition
      set description = #{agent.description}, system_prompt = #{agent.systemPrompt},
          variant = #{agent.variant}, config = cast(#{agent.configJson} as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = #{agent.name} and version = #{expectedVersion}
      """)
  int updateByName(
      @Param("agent") AgentDefinitionDO agent, @Param("expectedVersion") long expectedVersion);

  @Delete("delete from agent_definition where name = #{name} and version = #{expectedVersion}")
  int deleteByName(@Param("name") String name, @Param("expectedVersion") long expectedVersion);
}
