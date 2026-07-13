package fun.fengwk.kkstudio.core.agent.model.repo.impl.mapper;

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

import fun.fengwk.kkstudio.core.agent.model.repo.impl.model.AgentModelDO;

import java.util.List;

/**
 * @author fengwk
 */
@Mapper
public interface AgentModelMapper extends BaseMapper {

  @Select("select count(*) from agent_model where workspace_id = #{workspaceId}")
  long countByWorkspaceId(@Param("workspaceId") long workspaceId);

  @Select("select id, workspace_id, provider_id, name, description, capabilities_json, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_model where workspace_id = #{workspaceId} order by id asc limit #{offset}, #{limit}")
  @Results(
      id = "agentModelResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "workspace_id", property = "workspaceId"),
        @Result(column = "provider_id", property = "providerId"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "capabilities_json", property = "capabilitiesJson"),
        @Result(column = "config_json", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentModelDO> pageByWorkspaceId(
      @Param("workspaceId") long workspaceId, @Param("offset") long offset, @Param("limit") int limit);

  @Select("select id, workspace_id, provider_id, name, description, capabilities_json, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_model where id = #{id}")
  @ResultMap("agentModelResultMap")
  AgentModelDO getById(@Param("id") long id);

  @Select("select id, workspace_id, provider_id, name, description, capabilities_json, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_model where workspace_id = #{workspaceId} and id = #{id}")
  @ResultMap("agentModelResultMap")
  AgentModelDO getByWorkspaceIdAndId(@Param("workspaceId") long workspaceId, @Param("id") long id);

  @Select("select id, workspace_id, provider_id, name, description, capabilities_json, config_json, version, gmt_create as create_time, gmt_modified as update_time from agent_model where workspace_id = #{workspaceId} and name = #{name}")
  @ResultMap("agentModelResultMap")
  AgentModelDO getByWorkspaceIdAndName(@Param("workspaceId") long workspaceId, @Param("name") String name);

  @Insert("insert into agent_model (id, workspace_id, provider_id, name, description, capabilities_json, config_json, gmt_create, gmt_modified, version) values (#{id}, #{workspaceId}, #{providerId}, #{name}, #{description}, #{capabilitiesJson}, #{configJson}, current_timestamp(3), current_timestamp(3), 0)")
  int insert(AgentModelDO model);

  @Update("update agent_model set name = #{model.name}, description = #{model.description}, capabilities_json = #{model.capabilitiesJson}, config_json = #{model.configJson}, gmt_modified = current_timestamp(3), version = version + 1 where workspace_id = #{model.workspaceId} and id = #{model.id}")
  int updateById(@Param("model") AgentModelDO model);

  @Delete("delete from agent_model where workspace_id = #{workspaceId} and id = #{id}")
  int deleteByWorkspaceIdAndId(@Param("workspaceId") long workspaceId, @Param("id") long id);

  @Select("select count(*) from agent_definition where workspace_id = #{workspaceId} and model_id = #{modelId}")
  long countAgentsByModelId(@Param("workspaceId") long workspaceId, @Param("modelId") long modelId);
}
