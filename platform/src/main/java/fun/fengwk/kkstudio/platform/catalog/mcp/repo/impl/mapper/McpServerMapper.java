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
import java.util.UUID;

@Mapper
public interface McpServerMapper extends BaseMapper {

  String SERVER_COLUMNS =
      "id, name, connection_type, environment_id, connection_config::text as connection_config, "
          + "enabled, timeout_millis, discovery_status, discovered_version, version, "
          + "created_at as create_time, updated_at as update_time";

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
        @Result(column = "id", property = "id"),
        @Result(column = "name", property = "name"),
        @Result(column = "connection_type", property = "connectionType"),
        @Result(column = "environment_id", property = "environmentId"),
        @Result(column = "connection_config", property = "connectionConfig"),
        @Result(column = "enabled", property = "enabled"),
        @Result(column = "timeout_millis", property = "timeoutMillis"),
        @Result(column = "discovery_status", property = "discoveryStatus"),
        @Result(column = "discovered_version", property = "discoveredVersion"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<McpServerDO> page(@Param("offset") long offset, @Param("limit") int limit);

  @Select("select " + SERVER_COLUMNS + " from mcp_server where id = #{id}")
  @ResultMap("mcpServerResultMap")
  McpServerDO getById(@Param("id") UUID id);

  @Select("select " + SERVER_COLUMNS + " from mcp_server where id = #{id} for update")
  @ResultMap("mcpServerResultMap")
  McpServerDO getByIdForUpdate(@Param("id") UUID id);

  @Select("select " + SERVER_COLUMNS + " from mcp_server where name = #{name}")
  @ResultMap("mcpServerResultMap")
  McpServerDO getByName(@Param("name") String name);

  @Insert(
      """
      insert into mcp_server (
          id, name, connection_type, environment_id, connection_config,
          enabled, timeout_millis, discovery_status, discovered_version,
          created_at, updated_at, version
      ) values (
          #{id}, #{name}, #{connectionType}, #{environmentId}, cast(#{connectionConfig} as jsonb),
          #{enabled}, #{timeoutMillis}, #{discoveryStatus}, #{discoveredVersion},
          current_timestamp, current_timestamp, 0
      )
      """)
  int insert(McpServerDO server);

  @Update(
      """
      update mcp_server
      set connection_type = #{server.connectionType},
          environment_id = #{server.environmentId},
          connection_config = cast(#{server.connectionConfig} as jsonb),
          enabled = #{server.enabled},
          timeout_millis = #{server.timeoutMillis},
          discovery_status = 'UNVERIFIED',
          discovered_version = null,
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = #{server.id} and version = #{expectedVersion}
      """)
  int updateById(
      @Param("server") McpServerDO server, @Param("expectedVersion") long expectedVersion);

  @Update(
      """
      update mcp_server
      set discovery_status = #{discoveryStatus},
          discovered_version = #{discoveredVersion},
          updated_at = greatest(updated_at, current_timestamp)
      where id = #{id} and version = #{expectedVersion}
      """)
  int updateDiscoveryResult(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("discoveryStatus") String discoveryStatus,
      @Param("discoveredVersion") Long discoveredVersion);

  @Delete("delete from mcp_server where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);

  @Select(
      """
      select id, mcp_server_id, source_name, model_name, description,
             input_schema::text as input_schema_json, schema_revision, available
      from mcp_tool
      where id = #{toolId}
      """)
  @ResultMap("mcpToolResultMap")
  McpToolDO getToolById(@Param("toolId") UUID toolId);

  @Select(
      """
      select id, mcp_server_id, source_name, model_name, description,
             input_schema::text as input_schema_json, schema_revision, available
      from mcp_tool
      where mcp_server_id = #{serverId}
      order by model_name asc
      """)
  @Results(
      id = "mcpToolResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "mcp_server_id", property = "serverId"),
        @Result(column = "source_name", property = "sourceName"),
        @Result(column = "model_name", property = "modelName"),
        @Result(column = "description", property = "description"),
        @Result(column = "input_schema_json", property = "inputSchemaJson"),
        @Result(column = "schema_revision", property = "schemaRevision"),
        @Result(column = "available", property = "available")
      })
  List<McpToolDO> listTools(@Param("serverId") UUID serverId);

  @Select(
      """
      select id, mcp_server_id, source_name, model_name, description,
             input_schema::text as input_schema_json, schema_revision, available
      from mcp_tool
      where mcp_server_id = #{serverId} and available = true
      order by model_name asc
      """)
  @ResultMap("mcpToolResultMap")
  List<McpToolDO> listAvailableTools(@Param("serverId") UUID serverId);

  @Select(
      """
      select id, mcp_server_id, source_name, model_name, description,
             input_schema::text as input_schema_json, schema_revision, available
      from mcp_tool
      where available = true
      order by model_name asc
      """)
  @ResultMap("mcpToolResultMap")
  List<McpToolDO> listAllAvailableTools();

  @Select("select count(*) from mcp_tool where mcp_server_id = #{serverId} and available = true")
  int countAvailableTools(@Param("serverId") UUID serverId);

  @Insert(
      """
      insert into mcp_tool (
          id, mcp_server_id, source_name, model_name, description,
          input_schema, schema_revision, available
      ) values (
          #{id}, #{serverId}, #{sourceName}, #{modelName}, #{description},
          cast(#{inputSchemaJson} as jsonb), #{schemaRevision}, #{available}
      )
      """)
  int insertTool(McpToolDO tool);

  @Update(
      """
      update mcp_tool
      set description = #{description},
          input_schema = cast(#{inputSchemaJson} as jsonb),
          schema_revision = #{schemaRevision},
          available = #{available}
      where id = #{id}
      """)
  int updateTool(McpToolDO tool);

  @Delete("delete from mcp_tool where mcp_server_id = #{serverId} and source_name = #{sourceName}")
  int deleteTool(@Param("serverId") UUID serverId, @Param("sourceName") String sourceName);

  @Select("select count(*) from mcp_tool where model_name = #{modelName}")
  long countByModelName(@Param("modelName") String modelName);

  @Select(
      "select count(*) from mcp_tool where model_name = #{modelName} and id <> #{excludeToolId}")
  long countByModelNameExcluding(
      @Param("modelName") String modelName, @Param("excludeToolId") UUID excludeToolId);

  @Select(
      """
      select distinct tool_id
      from agent_definition,
           lateral jsonb_array_elements_text(
             case when jsonb_typeof(config -> 'toolIds') = 'array'
                  then config -> 'toolIds'
                  else '[]'::jsonb
             end
           ) as tool_id
      where tool_id in (select 'mcp.' || replace(id::text, '-', '') from mcp_tool)
      """)
  List<String> selectReferencedAgentToolIds();
}
