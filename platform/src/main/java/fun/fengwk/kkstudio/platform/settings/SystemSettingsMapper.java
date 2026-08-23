package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SystemSettingsMapper extends BaseMapper {

  String COLUMNS = "id, config, version, created_at as create_time, updated_at as update_time";

  @Select("select " + COLUMNS + " from system_setting where id = 1")
  @Results(
      id = "systemSettingResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "config", property = "configJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  SystemSettingsDO get();

  @Update(
      """
      update system_setting
      set config = cast(#{configJson} as jsonb),
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = 1 and version = #{expectedVersion}
      """)
  int updateByVersion(
      @Param("configJson") String configJson, @Param("expectedVersion") long expectedVersion);
}
