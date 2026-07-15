package fun.fengwk.kkstudio.core.workspace.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.workspace.repo.impl.model.WorkspaceDO;
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

/**
 * @author fengwk
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper {

  @Select("select count(*) from workspace")
  long countAll();

  @Select(
      "select id, name, settings_json, version, gmt_create as create_time, gmt_modified as"
          + " update_time from workspace order by id asc limit #{offset}, #{limit}")
  @Results(
      id = "workspaceResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "name", property = "name"),
        @Result(column = "settings_json", property = "settingsJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<WorkspaceDO> pageAll(@Param("offset") long offset, @Param("limit") int limit);

  @Select(
      "select id, name, settings_json, version, gmt_create as create_time, gmt_modified as"
          + " update_time from workspace where id = #{id}")
  @ResultMap("workspaceResultMap")
  WorkspaceDO getById(@Param("id") long id);

  @Select(
      "select id, name, settings_json, version, gmt_create as create_time, gmt_modified as"
          + " update_time from workspace where name = #{name}")
  @ResultMap("workspaceResultMap")
  WorkspaceDO getByName(@Param("name") String name);

  @Insert(
      "insert into workspace (id, name, settings_json, gmt_create, gmt_modified, version) values"
          + " (#{id}, #{name}, #{settingsJson}, current_timestamp(3), current_timestamp(3), 0)")
  int insert(WorkspaceDO workspace);

  @Update(
      "update workspace set name = #{workspace.name}, settings_json = #{workspace.settingsJson},"
          + " gmt_modified = current_timestamp(3), version = version + 1 where id ="
          + " #{workspace.id}")
  int updateById(@Param("workspace") WorkspaceDO workspace);

  @Delete("delete from workspace where id = #{id}")
  int deleteById(@Param("id") long id);

  @Select("""
      select count(*) from harness_session where workspace_id = #{id}
      """)
  long countResources(@Param("id") long id);
}
