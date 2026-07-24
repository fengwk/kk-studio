package fun.fengwk.kkstudio.core.harness.tool.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolArtifactDO;

@Mapper
public interface ToolArtifactMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_artifact (
          id, media_type, encoding, content, size_bytes, sha256, created_at
      ) values (
          #{id}, #{mediaType}, #{encoding}, #{content}, #{sizeBytes}, #{sha256}, #{createdAt}
      )
      """)
  int insert(ToolArtifactDO artifact);

  @Select(
      """
      select id, media_type, encoding, content, size_bytes, sha256, created_at
      from harness_artifact
      where id = #{id}
      """)
  @Results(
      id = "toolArtifactResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "media_type", property = "mediaType"),
        @Result(column = "encoding", property = "encoding"),
        @Result(column = "content", property = "content"),
        @Result(column = "size_bytes", property = "sizeBytes"),
        @Result(column = "sha256", property = "sha256"),
        @Result(column = "created_at", property = "createdAt")
      })
  ToolArtifactDO find(@Param("id") long id);
}
