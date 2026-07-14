package fun.fengwk.kkstudio.core.harness.tool.worker.store;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ToolArtifactMapper extends BaseMapper {
  @Insert(
      """
      insert into tool_artifact (
          id, workspace_id, media_type, encoding, content, size_bytes, sha256, gmt_create
      ) values (
          #{id}, #{workspaceId}, #{mediaType}, #{encoding}, #{content}, #{sizeBytes}, #{sha256}, #{createTime}
      )
      """)
  int insert(ToolArtifactDO artifact);

  @Select(
      """
      select id, workspace_id, media_type, encoding, content, size_bytes, sha256,
             gmt_create as create_time
      from tool_artifact
      where workspace_id = #{workspaceId} and id = #{id}
      """)
  @Results(
      id = "toolArtifactResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "workspace_id", property = "workspaceId"),
        @Result(column = "media_type", property = "mediaType"),
        @Result(column = "encoding", property = "encoding"),
        @Result(column = "content", property = "content"),
        @Result(column = "size_bytes", property = "sizeBytes"),
        @Result(column = "sha256", property = "sha256"),
        @Result(column = "create_time", property = "createTime")
      })
  ToolArtifactDO find(@Param("workspaceId") long workspaceId, @Param("id") long id);
}
