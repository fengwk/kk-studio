package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasCommandDO;

@Mapper
public interface CanvasCommandMapper extends BaseMapper {

  @Insert(
      """
      insert into canvas_command (
          id, command_id, workspace_id, canvas_id, base_revision, result_revision,
          request_hash, payload_json, result_json, gmt_create
      ) values (
          #{id}, #{commandId}, #{workspaceId}, #{canvasId}, #{baseRevision}, #{resultRevision},
          #{requestHash}, #{payloadJson}, #{resultJson}, current_timestamp(3)
      )
      """)
  int insert(CanvasCommandDO command);

  @Select(
      "select id, command_id, workspace_id, canvas_id, base_revision, result_revision,"
          + " request_hash, payload_json, result_json, gmt_create as create_time"
          + " from canvas_command where workspace_id = #{workspaceId} and command_id = #{commandId}")
  @Results({
    @Result(column = "id", property = "id"),
    @Result(column = "command_id", property = "commandId"),
    @Result(column = "workspace_id", property = "workspaceId"),
    @Result(column = "canvas_id", property = "canvasId"),
    @Result(column = "base_revision", property = "baseRevision"),
    @Result(column = "result_revision", property = "resultRevision"),
    @Result(column = "request_hash", property = "requestHash"),
    @Result(column = "payload_json", property = "payloadJson"),
    @Result(column = "result_json", property = "resultJson"),
    @Result(column = "create_time", property = "createTime")
  })
  CanvasCommandDO getByCommandId(
      @Param("workspaceId") long workspaceId, @Param("commandId") String commandId);
}
