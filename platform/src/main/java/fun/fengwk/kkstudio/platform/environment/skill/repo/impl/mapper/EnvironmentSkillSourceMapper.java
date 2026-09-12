package fun.fengwk.kkstudio.platform.environment.skill.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model.EnvironmentSkillSourceDO;

import java.util.List;
import java.util.UUID;

/**
 * {@code environment_skill_source} 的原子 SQL 入口。
 *
 * <p>全部读取按 {@code source_id} 稳定排序，保证同一次查询的确定性；行版本推进只走 CAS UPDATE，配置代际不依赖任何内存计数器。 {@code
 * diagnostics} 是 jsonb 列：写侧 {@code cast(? as jsonb)}，读侧按字符串取出后由 codec 严格解码。
 */
@Mapper
public interface EnvironmentSkillSourceMapper extends BaseMapper {

  String COLUMNS =
      "source_id, environment_id, source_type, path, default_source, git_url, git_ref, scan_path, "
          + "version, status, applied_version, applied_revision, diagnostics::text as diagnostics_json, "
          + "last_error_code, last_error_message, last_applied_at, "
          + "created_at as create_time, updated_at as update_time";

  @Results(
      id = "environmentSkillSourceResultMap",
      value = {
        @Result(column = "source_id", property = "sourceId"),
        @Result(column = "environment_id", property = "environmentId"),
        @Result(column = "source_type", property = "sourceType"),
        @Result(column = "path", property = "path"),
        @Result(column = "default_source", property = "defaultSource"),
        @Result(column = "git_url", property = "gitUrl"),
        @Result(column = "git_ref", property = "gitRef"),
        @Result(column = "scan_path", property = "scanPath"),
        @Result(column = "version", property = "version"),
        @Result(column = "status", property = "status"),
        @Result(column = "applied_version", property = "appliedVersion"),
        @Result(column = "applied_revision", property = "appliedRevision"),
        @Result(column = "diagnostics_json", property = "diagnosticsJson"),
        @Result(column = "last_error_code", property = "lastErrorCode"),
        @Result(column = "last_error_message", property = "lastErrorMessage"),
        @Result(column = "last_applied_at", property = "lastAppliedAt"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  @Select(
      "select "
          + COLUMNS
          + " from environment_skill_source where environment_id = #{environmentId} order by source_id asc")
  List<EnvironmentSkillSourceDO> listByEnvironment(@Param("environmentId") UUID environmentId);

  @ResultMap("environmentSkillSourceResultMap")
  @Select(
      "select "
          + COLUMNS
          + " from environment_skill_source where environment_id = #{environmentId} and source_id = #{sourceId}")
  EnvironmentSkillSourceDO get(
      @Param("environmentId") UUID environmentId, @Param("sourceId") UUID sourceId);

  @ResultMap("environmentSkillSourceResultMap")
  @Select(
      "select "
          + COLUMNS
          + " from environment_skill_source"
          + " where environment_id = #{environmentId} and source_id = #{sourceId} for update")
  EnvironmentSkillSourceDO lock(
      @Param("environmentId") UUID environmentId, @Param("sourceId") UUID sourceId);

  /**
   * 按 {@code source_id} 升序锁定该 Environment 的全部来源行。
   *
   * <p>集合代际推进与 READY 发布都先取这一组锁，保证同一 Environment 的并发行按同一顺序等待，不会形成锁环。
   */
  @ResultMap("environmentSkillSourceResultMap")
  @Select(
      "select "
          + COLUMNS
          + " from environment_skill_source where environment_id = #{environmentId}"
          + " order by source_id asc for update")
  List<EnvironmentSkillSourceDO> lockAllByEnvironment(@Param("environmentId") UUID environmentId);

  @Insert(
      """
      insert into environment_skill_source (
          source_id, environment_id, source_type, path, default_source, git_url, git_ref, scan_path,
          version, status, diagnostics, created_at, updated_at
      ) values (
          #{sourceId}, #{environmentId}, #{sourceType}, #{path}, #{defaultSource}, #{gitUrl},
          #{gitRef}, #{scanPath},
          0, 'UNAPPLIED', cast(#{diagnosticsJson} as jsonb), current_timestamp, current_timestamp
      )
      """)
  int insert(EnvironmentSkillSourceDO source);

  /**
   * CAS 整体替换来源配置并推进版本。
   *
   * <p>状态回到 {@code UNAPPLIED} 且清空 last error，但保留既有 applied 三元组与 skill 行：它们是"最近一次成功应用"的陈旧事实，
   * 供展示使用，不构成新规划候选。
   */
  @Update(
      """
      update environment_skill_source
      set source_type = #{source.sourceType},
          path = #{source.path},
          git_url = #{source.gitUrl},
          git_ref = #{source.gitRef},
          scan_path = #{source.scanPath},
          status = 'UNAPPLIED',
          diagnostics = cast(#{source.diagnosticsJson} as jsonb),
          last_error_code = null,
          last_error_message = null,
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where environment_id = #{source.environmentId}
        and source_id = #{source.sourceId}
        and version = #{expectedVersion}
      """)
  int updateByVersion(
      @Param("source") EnvironmentSkillSourceDO source,
      @Param("expectedVersion") long expectedVersion);

  /** 硬删除；该来源的 {@code environment_skill} 行由复合 FK 级联删除。 */
  @Delete(
      "delete from environment_skill_source where environment_id = #{environmentId} and source_id = #{sourceId} and version = #{expectedVersion}")
  int deleteByVersion(
      @Param("environmentId") UUID environmentId,
      @Param("sourceId") UUID sourceId,
      @Param("expectedVersion") long expectedVersion);

  /**
   * 把来源标记为 READY 并写入本次应用的 revision、诊断与时间，同时清空 last error。
   *
   * <p>{@code version} 是围栏：只有当行版本仍等于 {@code appliedVersion} 时才生效，因此并发配置更新不会让旧报告把 {@code
   * status=READY} 与陈旧 {@code applied_version} 组合成违反 schema check 的状态。
   */
  @Update(
      """
      update environment_skill_source
      set status = 'READY',
          applied_version = #{appliedVersion},
          applied_revision = #{appliedRevision},
          diagnostics = cast(#{diagnosticsJson} as jsonb),
          last_error_code = null,
          last_error_message = null,
          last_applied_at = current_timestamp,
          updated_at = greatest(updated_at, current_timestamp)
      where environment_id = #{environmentId}
        and source_id = #{sourceId}
        and version = #{appliedVersion}
      """)
  int markReadyByVersion(
      @Param("environmentId") UUID environmentId,
      @Param("sourceId") UUID sourceId,
      @Param("appliedVersion") long appliedVersion,
      @Param("appliedRevision") String appliedRevision,
      @Param("diagnosticsJson") String diagnosticsJson);

  /**
   * 按版本围栏将来源标记为 FAILED 并写入错误码与错误消息。
   *
   * <p>仅当行当前版本仍等于 {@code sourceVersion} 时生效；若已被并发配置更新推进则无操作返回 0。 既有 applied 三元组（{@code
   * applied_version}/{@code applied_revision}/{@code last_applied_at}）原样保留。
   */
  @Update(
      """
      update environment_skill_source
      set status = 'FAILED',
          last_error_code = #{safeCode},
          last_error_message = #{safeMessage},
          updated_at = greatest(updated_at, current_timestamp)
      where environment_id = #{environmentId}
        and source_id = #{sourceId}
        and version = #{sourceVersion}
      """)
  int markFailedByVersion(
      @Param("environmentId") UUID environmentId,
      @Param("sourceId") UUID sourceId,
      @Param("sourceVersion") long sourceVersion,
      @Param("safeCode") String safeCode,
      @Param("safeMessage") String safeMessage);

  @Select(
      "select count(1) > 0 from environment_skill_source where environment_id = #{environmentId}")
  boolean existsByEnvironment(@Param("environmentId") UUID environmentId);

  @Select(
      "select count(1) > 0 from environment_skill_source where environment_id = #{environmentId} and default_source")
  boolean existsDefaultByEnvironment(@Param("environmentId") UUID environmentId);
}
