package fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.model.AgentDefinitionDO;

import java.util.List;
import java.util.UUID;

@Mapper
public interface AgentDefinitionMapper extends BaseMapper {

  String COLUMNS =
      "name, description, system_prompt, model_provider_name, model_name, variant, environment_id, config, version, "
          + "created_at as create_time, updated_at as update_time";

  @Select("select count(*) from agent_definition")
  long count();

  @Select(
      "select "
          + COLUMNS
          + " from agent_definition"
          + " order by name asc limit #{limit} offset #{offset}")
  @Results(
      id = "agentDefinitionResultMap",
      value = {
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "system_prompt", property = "systemPrompt"),
        @Result(column = "model_provider_name", property = "modelProviderName"),
        @Result(column = "model_name", property = "modelName"),
        @Result(column = "variant", property = "variant"),
        @Result(column = "environment_id", property = "environmentId"),
        @Result(column = "config", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentDefinitionDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select("select " + COLUMNS + " from agent_definition where name = #{name}")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getByName(@Param("name") String name);

  @Select("select " + COLUMNS + " from agent_definition where name = #{name} for update")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getByNameForUpdate(@Param("name") String name);

  @Select(
      """
      select exists (
          select 1
          from agent_definition
          where name <> #{name}
            and jsonb_exists(config -> 'subagents', #{name})
      )
      """)
  boolean existsReferencingSubagent(@Param("name") String name);

  @Select(
      """
      select exists (
          select 1
          from agent_definition,
               lateral jsonb_array_elements(
                   case when jsonb_typeof(config -> 'skills') = 'array'
                        then config -> 'skills'
                        else '[]'::jsonb
                   end
               ) as skill_ref
          where environment_id = #{environmentId}
            and skill_ref ->> 'sourceId' = cast(#{sourceId} as text)
      )
      """)
  boolean existsReferencingSkillSource(
      @Param("environmentId") UUID environmentId, @Param("sourceId") UUID sourceId);

  @Select("select count(1) > 0 from agent_definition where environment_id = #{environmentId}")
  boolean existsByEnvironmentId(@Param("environmentId") UUID environmentId);

  @Insert(
      """
      insert into agent_definition (
          name, description, system_prompt, model_provider_name, model_name, variant, environment_id, config,
          created_at, updated_at, version
      ) values (
          #{name}, #{description}, #{systemPrompt}, #{modelProviderName}, #{modelName}, #{variant}, #{environmentId},
          cast(#{configJson} as jsonb), current_timestamp, current_timestamp, 0
      )
      """)
  int insert(AgentDefinitionDO agent);

  @Update(
      """
      update agent_definition
      set description = #{agent.description}, system_prompt = #{agent.systemPrompt},
          model_provider_name = #{agent.modelProviderName}, model_name = #{agent.modelName},
          variant = #{agent.variant}, environment_id = #{agent.environmentId},
          config = cast(#{agent.configJson} as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = #{agent.name} and version = #{expectedVersion}
      """)
  int updateByName(
      @Param("agent") AgentDefinitionDO agent, @Param("expectedVersion") long expectedVersion);

  /** 硬删除 CAS：行消失后同名立即可重建，删除失败只能来自 name 缺失或 version 不匹配。 */
  @Delete("delete from agent_definition where name = #{name} and version = #{expectedVersion}")
  int deleteByName(@Param("name") String name, @Param("expectedVersion") long expectedVersion);
}
