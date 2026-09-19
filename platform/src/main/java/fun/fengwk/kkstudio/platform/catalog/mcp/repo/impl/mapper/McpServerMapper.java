package fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpServerDO;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpToolDO;

import java.util.List;

@Mapper
public interface McpServerMapper extends BaseMapper {

  String SERVER_COLUMNS =
      "name, url, headers::text as headers_json, enabled, timeout_millis, discovery_status, "
          + "version, created_at as create_time, updated_at as update_time";

  @Select("select count(*) from mcp_server")
  long count();

  @Select("select " + SERVER_COLUMNS + " from mcp_server order by name asc")
  @ResultMap("mcpServerResultMap")
  List<McpServerDO> listAllServers();

  @Select(
      "select "
          + SERVER_COLUMNS
          + " from mcp_server order by name asc limit #{limit} offset #{offset}")
  @Results(
      id = "mcpServerResultMap",
      value = {
        @Result(column = "name", property = "name"),
        @Result(column = "url", property = "url"),
        @Result(column = "headers_json", property = "headersJson"),
        @Result(column = "enabled", property = "enabled"),
        @Result(column = "timeout_millis", property = "timeoutMillis"),
        @Result(column = "discovery_status", property = "discoveryStatus"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<McpServerDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select("select " + SERVER_COLUMNS + " from mcp_server where name = #{name}")
  @ResultMap("mcpServerResultMap")
  McpServerDO getByName(@Param("name") String name);

  @Select("select " + SERVER_COLUMNS + " from mcp_server where name = #{name} for update")
  @ResultMap("mcpServerResultMap")
  McpServerDO getForUpdate(@Param("name") String name);

  @Insert(
      """
      insert into mcp_server (
          name, url, headers, enabled, timeout_millis, discovery_status,
          created_at, updated_at, version
      ) values (
          #{name}, #{url}, cast(#{headersJson} as jsonb), #{enabled}, #{timeoutMillis},
          #{discoveryStatus}, current_timestamp, current_timestamp, 0
      )
      """)
  int insert(McpServerDO server);

  @Update(
      """
      update mcp_server
      set url = #{server.url},
          headers = cast(#{server.headersJson} as jsonb),
          enabled = #{server.enabled},
          timeout_millis = #{server.timeoutMillis},
          discovery_status = 'UNVERIFIED',
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where name = #{server.name} and version = #{expectedVersion}
      """)
  int update(@Param("server") McpServerDO server, @Param("expectedVersion") long expectedVersion);

  @Update(
      """
      update mcp_server
      set discovery_status = #{discoveryStatus},
          updated_at = greatest(updated_at, current_timestamp)
      where name = #{name} and version = #{expectedVersion}
      """)
  int updateDiscoveryStatus(
      @Param("name") String name,
      @Param("expectedVersion") long expectedVersion,
      @Param("discoveryStatus") String discoveryStatus);

  @Delete("delete from mcp_server where name = #{name} and version = #{expectedVersion}")
  int delete(@Param("name") String name, @Param("expectedVersion") long expectedVersion);

  String TOOL_COLUMNS =
      "name, server_name, source_name, description, input_schema::text as input_schema_json";

  @Select("select " + TOOL_COLUMNS + " from mcp_tool where name = #{name}")
  @ResultMap("mcpToolResultMap")
  McpToolDO getTool(@Param("name") String name);

  @Select(
      "select "
          + TOOL_COLUMNS
          + " from mcp_tool where server_name = #{serverName} order by name asc")
  @Results(
      id = "mcpToolResultMap",
      value = {
        @Result(column = "name", property = "name"),
        @Result(column = "server_name", property = "serverName"),
        @Result(column = "source_name", property = "sourceName"),
        @Result(column = "description", property = "description"),
        @Result(column = "input_schema_json", property = "inputSchemaJson")
      })
  List<McpToolDO> listTools(@Param("serverName") String serverName);

  @Select("select " + TOOL_COLUMNS + " from mcp_tool order by name asc")
  @ResultMap("mcpToolResultMap")
  List<McpToolDO> listAllTools();

  @Select("select count(*) from mcp_tool where server_name = #{serverName}")
  int countTools(@Param("serverName") String serverName);

  @Delete("delete from mcp_tool where server_name = #{serverName}")
  int deleteTools(@Param("serverName") String serverName);

  @Insert(
      """
      insert into mcp_tool (name, server_name, source_name, description, input_schema)
      values (#{name}, #{serverName}, #{sourceName}, #{description}, cast(#{inputSchemaJson} as jsonb))
      """)
  int insertTool(McpToolDO tool);

  @Select(
      """
      select distinct tool_name
      from agent_definition,
           lateral jsonb_array_elements_text(
             case when jsonb_typeof(config -> 'tools') = 'array'
                  then config -> 'tools'
                  else '[]'::jsonb
             end
           ) as tool_name
      where tool_name in (select name from mcp_tool)
      """)
  List<String> selectReferencedToolNames();
}
