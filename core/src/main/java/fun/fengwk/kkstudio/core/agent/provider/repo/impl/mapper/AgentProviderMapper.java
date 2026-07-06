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

/**
 * @author fengwk
 */
@Mapper
public interface AgentProviderMapper extends BaseMapper {

  @Select("select count(*) from agent_provider")
  long countAll();

  @Select(
      """
        select
            id,
            name,
            description,
            provider_type,
            base_url,
            api_key,
            timeout_millis,
            stream_idle_timeout_millis,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_provider
        order by id asc
        limit #{offset}, #{limit}
        """)
  @Results(
      id = "agentProviderResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "provider_type", property = "providerType"),
        @Result(column = "base_url", property = "baseUrl"),
        @Result(column = "api_key", property = "apiKey"),
        @Result(column = "timeout_millis", property = "timeoutMillis"),
        @Result(column = "stream_idle_timeout_millis", property = "streamIdleTimeoutMillis"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentProviderDO> pageAll(@Param("offset") long offset, @Param("limit") int limit);

  @Select(
      """
        select
            id,
            name,
            description,
            provider_type,
            base_url,
            api_key,
            timeout_millis,
            stream_idle_timeout_millis,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_provider
        where id = #{id}
        """)
  @ResultMap("agentProviderResultMap")
  AgentProviderDO getById(@Param("id") long id);

  @Select(
      """
        select
            id,
            name,
            description,
            provider_type,
            base_url,
            api_key,
            timeout_millis,
            stream_idle_timeout_millis,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_provider
        where name = #{name}
        """)
  @ResultMap("agentProviderResultMap")
  AgentProviderDO getByName(@Param("name") String name);

  @Insert(
      """
        insert into agent_provider (
            id,
            name,
            description,
            provider_type,
            base_url,
            api_key,
            timeout_millis,
            stream_idle_timeout_millis,
            gmt_create,
            gmt_modified,
            version
        ) values (
            #{id},
            #{name},
            #{description},
            #{providerType},
            #{baseUrl},
            #{apiKey},
            #{timeoutMillis},
            #{streamIdleTimeoutMillis},
            current_timestamp(3),
            current_timestamp(3),
            0
        )
        """)
  int insert(AgentProviderDO provider);

  @Update(
      """
        update agent_provider
        set
            name = #{provider.name},
            description = #{provider.description},
            provider_type = #{provider.providerType},
            base_url = #{provider.baseUrl},
            api_key = #{provider.apiKey},
            timeout_millis = #{provider.timeoutMillis},
            stream_idle_timeout_millis = #{provider.streamIdleTimeoutMillis},
            gmt_modified = current_timestamp(3)
        where id = #{provider.id}
        """)
  int updateById(@Param("provider") AgentProviderDO provider);

  @Delete("delete from agent_provider where id = #{id}")
  int deleteById(@Param("id") long id);

  @Select("select count(*) from agent_model where provider_id = #{providerId}")
  long countModelsByProviderId(@Param("providerId") long providerId);

  @Select("select count(*) from agent_definition where default_provider_id = #{providerId}")
  long countAgentsByProviderId(@Param("providerId") long providerId);
}
