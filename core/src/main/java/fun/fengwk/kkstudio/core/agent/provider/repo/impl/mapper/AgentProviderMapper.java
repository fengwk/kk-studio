package fun.fengwk.kkstudio.core.agent.provider.repo.impl.mapper;

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

import fun.fengwk.kkstudio.core.agent.provider.repo.impl.model.AgentProviderDO;

import java.util.List;

@Mapper
public interface AgentProviderMapper extends BaseMapper {

  String COLUMNS =
      "id, name, description, provider_type, base_url, credential, config, version, "
          + "created_at as create_time, updated_at as update_time";

  @Select("select count(*) from agent_provider")
  long count();

  @Select(
      "select " + COLUMNS + " from agent_provider order by id asc limit #{limit} offset #{offset}")
  @Results(
      id = "agentProviderResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "provider_type", property = "providerType"),
        @Result(column = "base_url", property = "baseUrl"),
        @Result(column = "credential", property = "credential"),
        @Result(column = "config", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentProviderDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select("select " + COLUMNS + " from agent_provider where id = #{id}")
  @ResultMap("agentProviderResultMap")
  AgentProviderDO getById(@Param("id") long id);

  @Select("select " + COLUMNS + " from agent_provider where name = #{name}")
  @ResultMap("agentProviderResultMap")
  AgentProviderDO getByName(@Param("name") String name);

  @Insert(
      """
      insert into agent_provider (
          id, name, description, provider_type, base_url, credential, config,
          created_at, updated_at, version
      ) values (
          #{id}, #{name}, #{description}, #{providerType}, #{baseUrl}, #{credential},
          cast(#{configJson} as jsonb), current_timestamp, current_timestamp, 0
      )
      """)
  int insert(AgentProviderDO provider);

  @Update(
      """
      update agent_provider
      set name = #{provider.name}, description = #{provider.description},
          provider_type = #{provider.providerType}, base_url = #{provider.baseUrl},
          credential = #{provider.credential}, config = cast(#{provider.configJson} as jsonb),
          updated_at = current_timestamp, version = version + 1
      where id = #{provider.id}
      """)
  int updateById(@Param("provider") AgentProviderDO provider);

  @Delete("delete from agent_provider where id = #{id}")
  int deleteById(@Param("id") long id);

  @Select("select count(*) from agent_model where provider_id = #{providerId}")
  long countModelsByProviderId(@Param("providerId") long providerId);
}
