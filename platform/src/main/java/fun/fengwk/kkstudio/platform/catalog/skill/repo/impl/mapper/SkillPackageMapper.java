package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillPackageDO;

import java.util.List;

/**
 * Platform 全局 Skill Package 权威表（{@code skill_package}）的原子 SQL 入口。
 *
 * <p>每个 {@code package_name} 恰一行：读路径不取锁，写路径先以 {@code for update} 锁行再按 {@code version} 做 CAS。{@code
 * skills} 由 {@code current_commit} 派生，与它在同一次 UPDATE 中原子切换。
 */
@Mapper
public interface SkillPackageMapper extends BaseMapper {

  @Results(
      id = "skillPackageResultMap",
      value = {
        @Result(column = "package_name", property = "packageName"),
        @Result(column = "description", property = "description"),
        @Result(column = "repository_url", property = "repositoryUrl"),
        @Result(column = "branch", property = "branch"),
        @Result(column = "current_commit", property = "currentCommit"),
        @Result(column = "observed_head_commit", property = "observedHeadCommit"),
        @Result(column = "head_checked_at", property = "headCheckedAt"),
        @Result(column = "head_check_error", property = "headCheckError"),
        @Result(column = "skills", property = "skillsJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  @Select(
      """
      select package_name, description, repository_url, branch, current_commit,
             observed_head_commit, head_checked_at, head_check_error, skills, version,
             create_time, update_time
      from skill_package
      where package_name = #{packageName}
      """)
  SkillPackageDO getPackage(@Param("packageName") String packageName);

  @ResultMap("skillPackageResultMap")
  @Select(
      """
      select package_name, description, repository_url, branch, current_commit,
             observed_head_commit, head_checked_at, head_check_error, skills, version,
             create_time, update_time
      from skill_package
      where package_name = #{packageName}
      for update
      """)
  SkillPackageDO lockPackage(@Param("packageName") String packageName);

  @ResultMap("skillPackageResultMap")
  @Select(
      """
      select package_name, description, repository_url, branch, current_commit,
             observed_head_commit, head_checked_at, head_check_error, skills, version,
             create_time, update_time
      from skill_package
      order by package_name asc
      """)
  List<SkillPackageDO> listPackages();

  @Insert(
      """
      insert into skill_package (
          package_name, description, repository_url, branch, current_commit,
          observed_head_commit, head_checked_at, head_check_error, skills, version,
          create_time, update_time
      ) values (
          #{skillPackage.packageName}, #{skillPackage.description}, #{skillPackage.repositoryUrl},
          #{skillPackage.branch}, #{skillPackage.currentCommit}, #{skillPackage.observedHeadCommit},
          #{skillPackage.headCheckedAt}, #{skillPackage.headCheckError},
          cast(#{skillPackage.skillsJson} as jsonb), #{skillPackage.version},
          current_timestamp, current_timestamp
      )
      """)
  int insertPackage(@Param("skillPackage") SkillPackageDO skillPackage);

  @Update(
      """
      update skill_package
      set description = #{skillPackage.description},
          branch = #{skillPackage.branch},
          current_commit = #{skillPackage.currentCommit},
          observed_head_commit = #{skillPackage.observedHeadCommit},
          head_checked_at = #{skillPackage.headCheckedAt},
          head_check_error = #{skillPackage.headCheckError},
          skills = cast(#{skillPackage.skillsJson} as jsonb),
          version = version + 1,
          update_time = greatest(update_time, current_timestamp)
      where package_name = #{skillPackage.packageName} and version = #{expectedVersion}
      """)
  int updatePackage(
      @Param("skillPackage") SkillPackageDO skillPackage,
      @Param("expectedVersion") long expectedVersion);

  @Delete(
      """
      delete from skill_package
      where package_name = #{packageName} and version = #{expectedVersion}
      """)
  int deletePackage(
      @Param("packageName") String packageName, @Param("expectedVersion") long expectedVersion);
}
