package fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.mapper;

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

import fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.model.CloudTextRevisionDO;

import java.util.List;
import java.util.UUID;

/** {@code cloud_text_revision} 的 MyBatis Mapper 接口。 */
@Mapper
public interface CloudTextRevisionMapper extends BaseMapper {

  String COLUMNS =
      "node_id, revision, content, size_bytes, sha256, is_current, created_at as create_time";

  @Results(
      id = "cloudTextRevisionResultMap",
      value = {
        @Result(column = "node_id", property = "nodeId"),
        @Result(column = "revision", property = "revision"),
        @Result(column = "content", property = "content"),
        @Result(column = "size_bytes", property = "sizeBytes"),
        @Result(column = "sha256", property = "sha256"),
        @Result(column = "is_current", property = "isCurrent"),
        @Result(column = "create_time", property = "createTime")
      })
  @Select(
      "select "
          + COLUMNS
          + " from cloud_text_revision where node_id = #{nodeId} and is_current = true")
  CloudTextRevisionDO getCurrentByNodeId(@Param("nodeId") UUID nodeId);

  @Select(
      "select "
          + COLUMNS
          + " from cloud_text_revision where node_id = #{nodeId} and revision = #{revision}")
  @ResultMap("cloudTextRevisionResultMap")
  CloudTextRevisionDO getByNodeIdAndRevision(
      @Param("nodeId") UUID nodeId, @Param("revision") long revision);

  @Select(
      "select "
          + COLUMNS
          + " from cloud_text_revision where node_id = #{nodeId} order by revision asc")
  @ResultMap("cloudTextRevisionResultMap")
  List<CloudTextRevisionDO> listByNodeId(@Param("nodeId") UUID nodeId);

  @Insert(
      """
      insert into cloud_text_revision (
          node_id, revision, content, size_bytes, sha256, is_current, created_at
      ) values (
          #{nodeId}, #{revision}, #{content}, #{sizeBytes}, #{sha256}, #{isCurrent},
          coalesce(#{createTime}, current_timestamp)
      )
      """)
  int insert(CloudTextRevisionDO revisionDO);

  @Update(
      """
      update cloud_text_revision
      set is_current = false
      where node_id = #{nodeId} and revision = #{expectedRevision} and is_current = true
      """)
  int unsetCurrent(@Param("nodeId") UUID nodeId, @Param("expectedRevision") long expectedRevision);

  @Delete("delete from cloud_text_revision where node_id = #{nodeId}")
  int deleteByNodeId(@Param("nodeId") UUID nodeId);
}
