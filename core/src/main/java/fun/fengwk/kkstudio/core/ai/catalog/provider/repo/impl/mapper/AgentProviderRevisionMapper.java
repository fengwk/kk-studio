package fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl.model.AgentProviderRevisionDO;

@Mapper
public interface AgentProviderRevisionMapper extends BaseMapper {

  String COLUMNS =
      "provider_name, provider_version, provider_type, base_url, credential, config, "
          + "created_at as create_time";

  @Select(
      "select "
          + COLUMNS
          + " from agent_provider_revision where provider_name = #{providerName}"
          + " and provider_version = #{providerVersion}")
  @Results(
      id = "agentProviderRevisionResultMap",
      value = {
        @Result(column = "provider_name", property = "providerName"),
        @Result(column = "provider_version", property = "providerVersion"),
        @Result(column = "provider_type", property = "providerType"),
        @Result(column = "base_url", property = "baseUrl"),
        @Result(column = "credential", property = "credential"),
        @Result(column = "config", property = "configJson"),
        @Result(column = "create_time", property = "createTime")
      })
  AgentProviderRevisionDO getByProviderNameAndVersion(
      @Param("providerName") String providerName, @Param("providerVersion") long providerVersion);

  @Insert(
      """
      insert into agent_provider_revision (
          provider_name, provider_version, provider_type, base_url, credential, config, created_at
      ) values (
          #{providerName}, #{providerVersion}, #{providerType}, #{baseUrl}, #{credential},
          cast(#{configJson} as jsonb), current_timestamp
      )
      """)
  int insert(AgentProviderRevisionDO revision);
}
