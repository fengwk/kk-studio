package fun.fengwk.kkstudio.platform.catalog.provider.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.catalog.provider.repo.impl.model.AgentProviderDO;

import java.util.List;

@Mapper
public interface AgentProviderMapper extends BaseMapper {

  String COLUMNS =
      "name, description, provider_type, base_url, credential, config, version, "
          + "created_at as create_time, updated_at as update_time";

  @Select("select count(*) from agent_provider")
  long count();

  @Select(
      "select "
          + COLUMNS
          + " from agent_provider"
          + " order by name asc limit #{limit} offset #{offset}")
  @Results(
      id = "agentProviderResultMap",
      value = {
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

  @Select("select " + COLUMNS + " from agent_provider where name = #{name}")
  @ResultMap("agentProviderResultMap")
  AgentProviderDO getByName(@Param("name") String name);

  @Select("select " + COLUMNS + " from agent_provider where name = #{name} for update")
  @ResultMap("agentProviderResultMap")
  AgentProviderDO getByNameForUpdate(@Param("name") String name);

  @Insert(
      """
      insert into agent_provider (
          name, description, provider_type, base_url, credential, config,
          created_at, updated_at, version
      ) values (
          #{name}, #{description}, #{providerType}, #{baseUrl}, #{credential},
          cast(#{configJson} as jsonb), current_timestamp, current_timestamp, 0
      )
      """)
  int insert(AgentProviderDO provider);

  @Update(
      """
      update agent_provider
      set description = #{provider.description}, provider_type = #{provider.providerType},
          base_url = #{provider.baseUrl},
          credential = #{provider.credential}, config = cast(#{provider.configJson} as jsonb),
          updated_at = greatest(updated_at, current_timestamp), version = version + 1
      where name = #{provider.name} and version = #{expectedVersion}
      """)
  int updateByName(
      @Param("provider") AgentProviderDO provider, @Param("expectedVersion") long expectedVersion);

  /** 硬删除 CAS：行消失后同名立即可重建，删除失败只能来自 name 缺失或 version 不匹配。 */
  @Delete("delete from agent_provider where name = #{name} and version = #{expectedVersion}")
  int deleteByName(@Param("name") String name, @Param("expectedVersion") long expectedVersion);

  @Select("select count(*) from agent_model where provider_name = #{providerName}")
  long countModelsByProviderName(@Param("providerName") String providerName);
}
