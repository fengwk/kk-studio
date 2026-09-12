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

import fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.model.CloudNodeDO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code cloud_node} 的 MyBatis Mapper 接口。 */
@Mapper
public interface CloudNodeMapper extends BaseMapper {

  String COLUMNS =
      "id, parent_id, name, kind, version, blob_id, created_at as create_time, updated_at as update_time";

  @Results(
      id = "cloudNodeResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "parent_id", property = "parentId"),
        @Result(column = "name", property = "name"),
        @Result(column = "kind", property = "kind"),
        @Result(column = "version", property = "version"),
        @Result(column = "blob_id", property = "blobId"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  @Select("select " + COLUMNS + " from cloud_node where id = #{id}")
  CloudNodeDO getById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from cloud_node where parent_id = #{parentId} and name = #{name}")
  @ResultMap("cloudNodeResultMap")
  CloudNodeDO getByParentIdAndName(@Param("parentId") UUID parentId, @Param("name") String name);

  @Select(
      "select "
          + COLUMNS
          + " from cloud_node where parent_id = #{parentId} and name = #{name} for update")
  @ResultMap("cloudNodeResultMap")
  CloudNodeDO getByParentIdAndNameForUpdate(
      @Param("parentId") UUID parentId, @Param("name") String name);

  @Select("select " + COLUMNS + " from cloud_node where parent_id is null and name = #{name}")
  @ResultMap("cloudNodeResultMap")
  CloudNodeDO getRootChildByName(@Param("name") String name);

  @Select(
      "select "
          + COLUMNS
          + " from cloud_node where parent_id is null and name = #{name} for update")
  @ResultMap("cloudNodeResultMap")
  CloudNodeDO getRootChildByNameForUpdate(@Param("name") String name);

  @Select("select " + COLUMNS + " from cloud_node where parent_id = #{parentId} order by name asc")
  @ResultMap("cloudNodeResultMap")
  List<CloudNodeDO> listByParentId(@Param("parentId") UUID parentId);

  @Select("select " + COLUMNS + " from cloud_node where parent_id is null order by name asc")
  @ResultMap("cloudNodeResultMap")
  List<CloudNodeDO> listRootChildren();

  @Select("select count(1) from cloud_node where parent_id = #{parentId}")
  int countByParentId(@Param("parentId") UUID parentId);

  @Select("select count(1) from cloud_node where parent_id is null")
  int countRootChildren();

  @Insert(
      """
      insert into cloud_node (
          id, parent_id, name, kind, version, blob_id, created_at, updated_at
      ) values (
          #{id}, #{parentId}, #{name}, #{kind}, #{version}, #{blobId},
          coalesce(#{createTime}, current_timestamp), coalesce(#{updateTime}, current_timestamp)
      )
      """)
  int insert(CloudNodeDO nodeDO);

  @Insert(
      """
      insert into cloud_node (
          id, parent_id, name, kind, version, blob_id, created_at, updated_at
      ) values (
          #{id}, #{parentId}, #{name}, #{kind}, #{version}, #{blobId},
          coalesce(#{createTime}, current_timestamp), coalesce(#{updateTime}, current_timestamp)
      )
      on conflict on constraint uk_cloud_node_parent_name do nothing
      """)
  int insertIfAbsent(CloudNodeDO nodeDO);

  @Update(
      """
      update cloud_node
      set parent_id = #{newParentId},
          name = #{newName},
          version = version + 1,
          updated_at = #{updatedAt}
      where id = #{id} and version = #{expectedVersion}
      """)
  int updateParentAndName(
      @Param("id") UUID id,
      @Param("newParentId") UUID newParentId,
      @Param("newName") String newName,
      @Param("expectedVersion") long expectedVersion,
      @Param("updatedAt") Instant updatedAt);

  @Update("update cloud_node set updated_at = #{updatedAt} where id = #{id}")
  int touch(@Param("id") UUID id, @Param("updatedAt") Instant updatedAt);

  @Delete("delete from cloud_node where id = #{id} and version = #{expectedVersion}")
  int deleteByIdAndVersion(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
