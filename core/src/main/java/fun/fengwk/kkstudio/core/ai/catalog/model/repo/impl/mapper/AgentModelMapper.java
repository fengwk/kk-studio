package fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.model.AgentModelDO;

import java.util.List;

@Mapper
public interface AgentModelMapper extends BaseMapper {

  String COLUMNS =
      "provider_name, name, description, config, version, "
          + "created_at as create_time, updated_at as update_time, deleted_at";

  @Select("select count(*) from agent_model where deleted_at is null")
  long count();

  @Select(
      "select "
          + COLUMNS
          + " from agent_model where deleted_at is null"
          + " order by provider_name asc, name asc limit #{limit} offset #{offset}")
  @Results(
      id = "agentModelResultMap",
      value = {
        @Result(column = "provider_name", property = "providerName"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "config", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime"),
        @Result(column = "deleted_at", property = "deletedAt")
      })
  List<AgentModelDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select(
      "select "
          + COLUMNS
          + " from agent_model where provider_name = #{providerName} and name = #{name}"
          + " and deleted_at is null")
  @ResultMap("agentModelResultMap")
  AgentModelDO getByProviderNameAndName(
      @Param("providerName") String providerName, @Param("name") String name);

  @Select(
      "select "
          + COLUMNS
          + " from agent_model where provider_name = #{providerName} and name = #{name}"
          + " and deleted_at is null for update")
  @ResultMap("agentModelResultMap")
  AgentModelDO getByProviderNameAndNameForUpdate(
      @Param("providerName") String providerName, @Param("name") String name);

  @Insert(
      """
      insert into agent_model (
          provider_name, name, description, config,
          created_at, updated_at, version
      ) values (
          #{providerName}, #{name}, #{description}, cast(#{configJson} as jsonb),
          current_timestamp, current_timestamp, 0
      )
      """)
  int insert(AgentModelDO model);

  @Update(
      """
      update agent_model
      set description = #{model.description}, config = cast(#{model.configJson} as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where provider_name = #{model.providerName} and name = #{model.name}
        and version = #{expectedVersion} and deleted_at is null
      """)
  int updateByName(
      @Param("model") AgentModelDO model, @Param("expectedVersion") long expectedVersion);

  @Update(
      """
      update agent_model
      set deleted_at = current_timestamp,
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where provider_name = #{providerName} and name = #{name}
        and version = #{expectedVersion} and deleted_at is null
      """)
  int deleteByName(
      @Param("providerName") String providerName,
      @Param("name") String name,
      @Param("expectedVersion") long expectedVersion);

  @Select(
      "select count(*) from agent_definition where model_provider_name = #{providerName} "
          + "and model_name = #{name} and deleted_at is null")
  long countAgentsByModelName(
      @Param("providerName") String providerName, @Param("name") String name);
}
