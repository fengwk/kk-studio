package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillDO;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillPackageDO;

import java.util.Collection;
import java.util.List;

/**
 * Platform 全局 Skill 目录（{@code skill_package} / {@code skill}）的原子 SQL 入口。
 *
 * <p>两张表构成同一个聚合：package 版本行只表达版本身份与活跃状态，skill 行持有该版本内的精确内容事实。读路径不取锁；写路径的锁顺序固定为「活跃 package 行 → 相关活跃
 * skill 行（按 name 升序）」，Agent 引用校验只按 name 升序锁活跃 skill 行，因此与 package 替换/删除串行化且不成环。
 *
 * <p>内容行永不物理删除：替换只插入新版本并切换 {@code active}，删除只把当前版本的行置为非活跃。
 */
@Mapper
public interface SkillCatalogMapper extends BaseMapper {

  @Results(
      id = "skillPackageResultMap",
      value = {
        @Result(column = "package_name", property = "packageName"),
        @Result(column = "package_version", property = "packageVersion"),
        @Result(column = "description", property = "description"),
        @Result(column = "active", property = "active"),
        @Result(column = "create_time", property = "createTime")
      })
  @Select(
      """
      select package_name, package_version, description, active, create_time
      from skill_package
      where package_name = #{packageName} and package_version = #{packageVersion}
      """)
  SkillPackageDO getPackage(
      @Param("packageName") String packageName, @Param("packageVersion") String packageVersion);

  @ResultMap("skillPackageResultMap")
  @Select(
      """
      select package_name, package_version, description, active, create_time
      from skill_package
      where package_name = #{packageName} and active
      """)
  SkillPackageDO getActivePackage(@Param("packageName") String packageName);

  @ResultMap("skillPackageResultMap")
  @Select(
      """
      select package_name, package_version, description, active, create_time
      from skill_package
      where package_name = #{packageName} and active
      for update
      """)
  SkillPackageDO lockActivePackage(@Param("packageName") String packageName);

  @ResultMap("skillPackageResultMap")
  @Select(
      """
      select package_name, package_version, description, active, create_time
      from skill_package
      where active
      order by package_name asc, package_version asc
      """)
  List<SkillPackageDO> listActivePackages();

  @Select(
      """
      select exists (
          select 1 from skill_package
          where package_name = #{packageName} and package_version = #{packageVersion}
      )
      """)
  boolean existsPackage(
      @Param("packageName") String packageName, @Param("packageVersion") String packageVersion);

  @Insert(
      """
      insert into skill_package (
          package_name, package_version, description, active, create_time
      ) values (
          #{skillPackage.packageName}, #{skillPackage.packageVersion}, #{skillPackage.description},
          #{skillPackage.active}, current_timestamp
      )
      """)
  int insertPackage(@Param("skillPackage") SkillPackageDO skillPackage);

  @Update(
      """
      update skill_package
      set active = #{active}
      where package_name = #{packageName} and package_version = #{packageVersion}
      """)
  int setPackageActive(
      @Param("packageName") String packageName,
      @Param("packageVersion") String packageVersion,
      @Param("active") boolean active);

  @Results(
      id = "skillResultMap",
      value = {
        @Result(column = "package_name", property = "packageName"),
        @Result(column = "package_version", property = "packageVersion"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "content", property = "content"),
        @Result(column = "active", property = "active"),
        @Result(column = "create_time", property = "createTime")
      })
  @Select(
      """
      select package_name, package_version, name, description, content, active, create_time
      from skill
      where package_name = #{packageName} and package_version = #{packageVersion}
        and name = #{name}
      """)
  SkillDO getSkill(
      @Param("packageName") String packageName,
      @Param("packageVersion") String packageVersion,
      @Param("name") String name);

  @ResultMap("skillResultMap")
  @Select(
      """
      select package_name, package_version, name, description, content, active, create_time
      from skill
      where active
      order by name asc
      """)
  List<SkillDO> listActiveSkills();

  @ResultMap("skillResultMap")
  @Select(
      """
      select package_name, package_version, name, description, content, active, create_time
      from skill
      where package_name = #{packageName} and package_version = #{packageVersion} and active
      order by name asc
      """)
  List<SkillDO> listActiveSkillsByPackage(
      @Param("packageName") String packageName, @Param("packageVersion") String packageVersion);

  @ResultMap("skillResultMap")
  @Select(
      """
      select package_name, package_version, name, description, content, active, create_time
      from skill
      where name = #{name} and active
      """)
  SkillDO getActiveSkill(@Param("name") String name);

  @ResultMap("skillResultMap")
  @Select(
      """
      <script>
      select package_name, package_version, name, description, content, active, create_time
      from skill
      where active and name in
      <foreach item="name" collection="names" open="(" separator="," close=")">
          #{name}
      </foreach>
      order by name asc
      for update
      </script>
      """)
  List<SkillDO> lockActiveSkillsByNames(@Param("names") Collection<String> names);

  @Insert(
      """
      <script>
      insert into skill (
          package_name, package_version, name, description, content, active, create_time
      ) values
      <foreach item="skill" collection="skills" separator=",">
          (#{skill.packageName}, #{skill.packageVersion}, #{skill.name}, #{skill.description},
           #{skill.content}, #{skill.active}, current_timestamp)
      </foreach>
      </script>
      """)
  int insertSkills(@Param("skills") List<SkillDO> skills);

  @Update(
      """
      update skill
      set active = #{active}
      where package_name = #{packageName} and package_version = #{packageVersion}
      """)
  int setSkillsActiveByPackage(
      @Param("packageName") String packageName,
      @Param("packageVersion") String packageVersion,
      @Param("active") boolean active);
}
