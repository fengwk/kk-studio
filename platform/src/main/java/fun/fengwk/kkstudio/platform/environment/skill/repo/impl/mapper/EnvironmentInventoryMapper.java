package fun.fengwk.kkstudio.platform.environment.skill.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model.EnvironmentInventoryDO;

import java.util.UUID;

/**
 * {@code environment_inventory} 的原子 SQL 入口。
 *
 * <p>该表每个 Environment 恰一行。{@code source_set_version} 只有在来源集合成员关系变化时才 +1，而 READY 发布必须同时满足 "集合代际相等" 与
 * "applied 不回退" 两个条件，因此延迟到达的旧报告不能覆盖更新的期望代际。
 */
@Mapper
public interface EnvironmentInventoryMapper extends BaseMapper {

  String COLUMNS =
      "environment_id, source_set_version, applied_source_set_version, capabilities_version, "
          + "operating_system, time_zone, note, root_path, owner_node_id, lease_token, reported_at, "
          + "created_at as create_time, updated_at as update_time";

  @Results(
      id = "environmentInventoryResultMap",
      value = {
        @Result(column = "environment_id", property = "environmentId"),
        @Result(column = "source_set_version", property = "sourceSetVersion"),
        @Result(column = "applied_source_set_version", property = "appliedSourceSetVersion"),
        @Result(column = "capabilities_version", property = "capabilitiesVersion"),
        @Result(column = "operating_system", property = "operatingSystem"),
        @Result(column = "time_zone", property = "timeZone"),
        @Result(column = "note", property = "note"),
        @Result(column = "root_path", property = "rootPath"),
        @Result(column = "owner_node_id", property = "ownerNodeId"),
        @Result(column = "lease_token", property = "leaseToken"),
        @Result(column = "reported_at", property = "reportedAt"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  @Select(
      "select " + COLUMNS + " from environment_inventory where environment_id = #{environmentId}")
  EnvironmentInventoryDO get(@Param("environmentId") UUID environmentId);

  @ResultMap("environmentInventoryResultMap")
  @Select(
      "select "
          + COLUMNS
          + " from environment_inventory where environment_id = #{environmentId} for update")
  EnvironmentInventoryDO lock(@Param("environmentId") UUID environmentId);

  @Insert(
      """
      insert into environment_inventory (environment_id, source_set_version, created_at, updated_at)
      values (#{environmentId}, 0, current_timestamp, current_timestamp)
      """)
  int insert(@Param("environmentId") UUID environmentId);

  /**
   * 来源集合成员关系变化时前进期望代际。
   *
   * <p>调用方必须先持有同一个 Environment 的 inventory 行锁，因此这里的读-改-写是串行的，不依赖重试循环。
   */
  @Update(
      """
      update environment_inventory
      set source_set_version = source_set_version + 1,
          updated_at = greatest(updated_at, current_timestamp)
      where environment_id = #{environmentId}
      """)
  int incrementSourceSetVersion(@Param("environmentId") UUID environmentId);

  /**
   * READY 发布推进 inventory：只在集合代际相等且 applied 不回退时生效。
   *
   * <p>同一条语句同时写入报告列与 {@code applied_source_set_version}，因此报告列与 applied 代际永远同生同灭；任何候选检查
   * 失败都必须先于本语句发生，保证失败的报告不留下部分事实。
   */
  @Update(
      """
      update environment_inventory
      set applied_source_set_version = #{sourceSetVersion},
          capabilities_version = #{capabilitiesVersion},
          operating_system = #{operatingSystem},
          time_zone = #{timeZone},
          note = #{note},
          root_path = #{rootPath},
          owner_node_id = #{ownerNodeId},
          lease_token = #{leaseToken},
          reported_at = current_timestamp,
          updated_at = greatest(updated_at, current_timestamp)
      where environment_id = #{environmentId}
        and source_set_version = #{sourceSetVersion}
        and (applied_source_set_version is null or applied_source_set_version <= #{sourceSetVersion})
      """)
  int applyReadyReport(
      @Param("environmentId") UUID environmentId,
      @Param("sourceSetVersion") long sourceSetVersion,
      @Param("capabilitiesVersion") int capabilitiesVersion,
      @Param("operatingSystem") String operatingSystem,
      @Param("timeZone") String timeZone,
      @Param("note") String note,
      @Param("rootPath") String rootPath,
      @Param("ownerNodeId") UUID ownerNodeId,
      @Param("leaseToken") UUID leaseToken);
}
