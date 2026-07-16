package fun.fengwk.kkstudio.core.environment.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.environment.repo.impl.model.ToolEnvironmentDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ToolEnvironmentMapper extends BaseMapper {

  String COLUMNS =
      "id, name, description, capabilities_json, last_seen_at, version, "
          + "gmt_create as create_time, gmt_modified as update_time";

  @Select("select count(*) from tool_environment")
  long count();

  @Select("select " + COLUMNS + " from tool_environment order by id asc limit #{offset}, #{limit}")
  @Results(
      id = "toolEnvironmentResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "capabilities_json", property = "capabilitiesJson"),
        @Result(column = "last_seen_at", property = "lastSeenAt"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<ToolEnvironmentDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select("select " + COLUMNS + " from tool_environment where id = #{id}")
  @ResultMap("toolEnvironmentResultMap")
  ToolEnvironmentDO getById(@Param("id") long id);

  @Select("select " + COLUMNS + " from tool_environment where name = #{name}")
  @ResultMap("toolEnvironmentResultMap")
  ToolEnvironmentDO getByName(@Param("name") String name);

  @Insert(
      """
      insert into tool_environment (
          id, name, description, capabilities_json, last_seen_at,
          gmt_create, gmt_modified, version
      ) values (
          #{id}, #{name}, #{description}, #{capabilitiesJson}, #{lastSeenAt},
          current_timestamp(3), current_timestamp(3), 0
      )
      """)
  int insert(ToolEnvironmentDO environment);

  @Update(
      """
      update tool_environment
      set name = #{environment.name}, description = #{environment.description},
          gmt_modified = current_timestamp(3), version = version + 1
      where id = #{environment.id}
      """)
  int updateById(@Param("environment") ToolEnvironmentDO environment);

  @Delete("delete from tool_environment where id = #{id}")
  int deleteById(@Param("id") long id);

  @Update(
      """
      update tool_environment
      set capabilities_json = #{capabilitiesJson},
          last_seen_at = #{lastSeenAt},
          gmt_modified = current_timestamp(3),
          version = version + 1
      where id = #{id}
      """)
  int updateCapabilities(
      @Param("id") long id,
      @Param("capabilitiesJson") String capabilitiesJson,
      @Param("lastSeenAt") LocalDateTime lastSeenAt);

  @Update(
      """
      update tool_environment
      set last_seen_at = #{lastSeenAt},
          gmt_modified = current_timestamp(3)
      where id = #{id}
      """)
  int heartbeat(@Param("id") long id, @Param("lastSeenAt") LocalDateTime lastSeenAt);

  @Select("select count(*) from tool_invocation where environment_id = #{environmentId}")
  long countToolInvocations(@Param("environmentId") long environmentId);
}
