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

  @Select("select count(*) from agent_definition where workspace_id = #{workspaceId}")
  long countByWorkspaceId(@Param("workspaceId") long workspaceId);

  @Select("select id, workspace_id, name, description, system_prompt, model_id, variant, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_definition where workspace_id = #{workspaceId} order by id asc limit #{offset}, #{limit}")
  @Results(
      id = "agentDefinitionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "workspace_id", property = "workspaceId"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "system_prompt", property = "systemPrompt"),
        @Result(column = "model_id", property = "modelId"),
        @Result(column = "variant", property = "variant"),
        @Result(column = "config_json", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentDefinitionDO> pageByWorkspaceId(
      @Param("workspaceId") long workspaceId, @Param("offset") long offset, @Param("limit") int limit);

  @Select("select id, workspace_id, name, description, system_prompt, model_id, variant, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_definition where id = #{id}")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getById(@Param("id") long id);

  @Select("select id, workspace_id, name, description, system_prompt, model_id, variant, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_definition where workspace_id = #{workspaceId} and id = #{id}")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getByWorkspaceIdAndId(@Param("workspaceId") long workspaceId, @Param("id") long id);

  @Select("select id, workspace_id, name, description, system_prompt, model_id, variant, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_definition where workspace_id = #{workspaceId} and name = #{name}")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getByWorkspaceIdAndName(@Param("workspaceId") long workspaceId, @Param("name") String name);

  @Select("select id, workspace_id, name, description, system_prompt, model_id, variant, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_definition where name = #{name} order by id asc limit 1")
  @ResultMap("agentDefinitionResultMap")
  AgentDefinitionDO getByName(@Param("name") String name);

  @Insert("insert into agent_definition (id, workspace_id, name, description, system_prompt, model_id, variant, config_json, gmt_create, gmt_modified, version) values (#{id}, #{workspaceId}, #{name}, #{description}, #{systemPrompt}, #{modelId}, #{variant}, #{configJson}, current_timestamp(3), current_timestamp(3), 0)")
  int insert(AgentDefinitionDO agent);

  @Update("update agent_definition set name = #{agent.name}, description = #{agent.description}, system_prompt = #{agent.systemPrompt}, model_id = #{agent.modelId}, variant = #{agent.variant}, config_json = #{agent.configJson}, gmt_modified = current_timestamp(3), version = version + 1 where workspace_id = #{agent.workspaceId} and id = #{agent.id}")
  int updateById(@Param("agent") AgentDefinitionDO agent);

  @Delete("delete from agent_definition where workspace_id = #{workspaceId} and id = #{id}")
  int deleteByWorkspaceIdAndId(@Param("workspaceId") long workspaceId, @Param("id") long id);
}
