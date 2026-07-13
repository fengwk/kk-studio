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

/**
 * @author fengwk
 */
@Mapper
public interface AgentDefinitionMapper extends BaseMapper {

  @Select("select count(*) from agent_definition")
  long countAll();

  @Select(
      """
        select
            id,
            name,
            description,
            system_prompt,
            default_provider_id,
            default_model_id,
            default_variant,
            tools_json,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_definition
        order by id asc
        limit #{offset}, #{limit}
        """)
  @Results(
      id = "agentDefinitionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "system_prompt", property = "systemPrompt"),
        @Result(column = "default_provider_id", property = "defaultProviderId"),
        @Result(column = "default_model_id", property = "defaultModelId"),
        @Result(column = "default_variant", property = "defaultVariant"),
        @Result(column = "tools_json", property = "toolsJson"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentDefinitionDO> pageAll(@Param("offset") long offset, @Param("limit") int limit);

  @Select(
      """
        select
            id,
            name,
            description,
            system_prompt,
            default_provider_id,
            default_model_id,
            default_variant,
            tools_json,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_definition
        where id = #{id}
        """)
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getById(@Param("id") long id);

  @Select(
      """
        select
            id,
            name,
            description,
            system_prompt,
            default_provider_id,
            default_model_id,
            default_variant,
            tools_json,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_definition
        where name = #{name}
        """)
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getByName(@Param("name") String name);

  @Insert(
      """
        insert into agent_definition (
            id,
            name,
            description,
            system_prompt,
            default_provider_id,
            default_model_id,
            default_variant,
            tools_json,
            gmt_create,
            gmt_modified,
            version
        ) values (
            #{id},
            #{name},
            #{description},
            #{systemPrompt},
            #{defaultProviderId},
            #{defaultModelId},
            #{defaultVariant},
            #{toolsJson},
            current_timestamp(3),
            current_timestamp(3),
            0
        )
        """)
  int insert(AgentDefinitionDO agent);

  @Update(
      """
        update agent_definition
        set
            name = #{agent.name},
            description = #{agent.description},
            system_prompt = #{agent.systemPrompt},
            default_provider_id = #{agent.defaultProviderId},
            default_model_id = #{agent.defaultModelId},
            default_variant = #{agent.defaultVariant},
            tools_json = #{agent.toolsJson},
            gmt_modified = current_timestamp(3)
        where id = #{agent.id}
        """)
  int updateById(@Param("agent") AgentDefinitionDO agent);

  @Delete("delete from agent_definition where id = #{id}")
  int deleteById(@Param("id") long id);
}
