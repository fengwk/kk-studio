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

@Mapper
public interface AgentModelMapper extends BaseMapper {

  String COLUMNS =
      "id, provider_id, name, description, config, version, "
          + "created_at as create_time, updated_at as update_time";

  @Select("select count(*) from agent_model")
  long count();

  @Select("select " + COLUMNS + " from agent_model order by id asc limit #{limit} offset #{offset}")
  @Results(
      id = "agentModelResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "provider_id", property = "providerId"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "config", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentModelDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select("select " + COLUMNS + " from agent_model where id = #{id}")
  @ResultMap("agentModelResultMap")
  AgentModelDO getById(@Param("id") long id);

  @Select(
      "select "
          + COLUMNS
          + " from agent_model where provider_id = #{providerId} and name = #{name}")
  @ResultMap("agentModelResultMap")
  AgentModelDO getByProviderIdAndName(
      @Param("providerId") long providerId, @Param("name") String name);

  @Insert(
      """
      insert into agent_model (
          id, provider_id, name, description, config,
          created_at, updated_at, version
      ) values (
          #{id}, #{providerId}, #{name}, #{description}, cast(#{configJson} as jsonb),
          current_timestamp, current_timestamp, 0
      )
      """)
  int insert(AgentModelDO model);

  @Update(
      """
      update agent_model
      set name = #{model.name}, description = #{model.description},
          config = cast(#{model.configJson} as jsonb),
          updated_at = current_timestamp, version = version + 1
      where id = #{model.id} and version = #{expectedVersion}
      """)
  int updateById(
      @Param("model") AgentModelDO model, @Param("expectedVersion") long expectedVersion);

  @Delete("delete from agent_model where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") long id, @Param("expectedVersion") long expectedVersion);

  @Select("select count(*) from agent_definition where model_id = #{modelId}")
  long countAgentsByModelId(@Param("modelId") long modelId);
}
