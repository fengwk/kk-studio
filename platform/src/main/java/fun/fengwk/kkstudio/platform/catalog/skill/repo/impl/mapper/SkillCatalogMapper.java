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

import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.CurrentSkillDO;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillPackageDO;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillRevisionDO;

import java.util.Collection;
import java.util.List;

/**
 * Platform 全局 Skill 目录（{@code skill_package} / {@code skill_revision} / {@code skill}）的原子 SQL 入口。
 *
 * <p>三张表构成同一个聚合：不可变的 package 版本与其 revision 定义内容事实，当前目录行只持有指向 revision 的身份。读路径不取锁； 写路径的锁顺序固定为「活跃
 * package 行 → 该 package 的当前 skill 行（按 name 升序）」，Agent 引用校验只按 name 升序锁当前 skill 行，因此与 package
 * 替换/删除串行化且不成环。
 */
@Mapper
public interface SkillCatalogMapper extends BaseMapper {

  @Results(
      id = "skillPackageResultMap",
      value = {
        @Result(column = "package_name", property = "packageName"),
        @Result(column = "package_version", property = "packageVersion"),
        @Result(column = "description", property = "description"),
        @Result(column = "package_revision", property = "packageRevision"),
        @Result(column = "active", property = "active"),
        @Result(column = "create_time", property = "createTime")
      })
  @Select(
      """
      select package_name, package_version, description, package_revision, active, create_time
      from skill_package
      where package_name = #{packageName} and package_version = #{packageVersion}
      """)
  SkillPackageDO getPackage(
      @Param("packageName") String packageName, @Param("packageVersion") String packageVersion);

  @ResultMap("skillPackageResultMap")
  @Select(
      """
      select package_name, package_version, description, package_revision, active, create_time
      from skill_package
      where package_name = #{packageName} and active
      """)
  SkillPackageDO getActivePackage(@Param("packageName") String packageName);

  @ResultMap("skillPackageResultMap")
  @Select(
      """
      select package_name, package_version, description, package_revision, active, create_time
      from skill_package
      where package_name = #{packageName} and active
      for update
      """)
  SkillPackageDO lockActivePackage(@Param("packageName") String packageName);

  @ResultMap("skillPackageResultMap")
  @Select(
      """
      select package_name, package_version, description, package_revision, active, create_time
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
          package_name, package_version, description, package_revision, active, create_time
      ) values (
          #{skillPackage.packageName}, #{skillPackage.packageVersion}, #{skillPackage.description},
          #{skillPackage.packageRevision}, #{skillPackage.active}, current_timestamp
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
      id = "skillRevisionResultMap",
      value = {
        @Result(column = "package_name", property = "packageName"),
        @Result(column = "package_version", property = "packageVersion"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "content", property = "content"),
        @Result(column = "content_revision", property = "contentRevision"),
        @Result(column = "create_time", property = "createTime")
      })
  @Select(
      """
      select package_name, package_version, name, description, content, content_revision,
             create_time
      from skill_revision
      where package_name = #{packageName} and package_version = #{packageVersion}
        and name = #{name}
      """)
  SkillRevisionDO getRevision(
      @Param("packageName") String packageName,
      @Param("packageVersion") String packageVersion,
      @Param("name") String name);

  @ResultMap("skillRevisionResultMap")
  @Select(
      """
      select package_name, package_version, name, description, content, content_revision,
             create_time
      from skill_revision
      where package_name = #{packageName} and package_version = #{packageVersion}
      order by name asc
      """)
  List<SkillRevisionDO> listRevisions(
      @Param("packageName") String packageName, @Param("packageVersion") String packageVersion);

  @Insert(
      """
      <script>
      insert into skill_revision (
          package_name, package_version, name, description, content, content_revision,
          create_time
      ) values
      <foreach item="revision" collection="revisions" separator=",">
          (#{revision.packageName}, #{revision.packageVersion}, #{revision.name},
           #{revision.description}, #{revision.content}, #{revision.contentRevision},
           current_timestamp)
      </foreach>
      </script>
      """)
  int insertRevisions(@Param("revisions") List<SkillRevisionDO> revisions);

  @Results(
      id = "currentSkillResultMap",
      value = {
        @Result(column = "name", property = "name"),
        @Result(column = "package_name", property = "packageName"),
        @Result(column = "package_version", property = "packageVersion"),
        @Result(column = "description", property = "description"),
        @Result(column = "content_revision", property = "contentRevision"),
        @Result(column = "content", property = "content")
      })
  @Select(
      """
      select skill.name, skill.package_name, skill.package_version,
             revision.description, revision.content_revision, revision.content
      from skill
      join skill_revision as revision
        on revision.package_name = skill.package_name
       and revision.package_version = skill.package_version
       and revision.name = skill.name
      order by skill.name asc
      """)
  List<CurrentSkillDO> listCurrentSkills();

  @ResultMap("currentSkillResultMap")
  @Select(
      """
      select skill.name, skill.package_name, skill.package_version,
             revision.description, revision.content_revision, revision.content
      from skill
      join skill_revision as revision
        on revision.package_name = skill.package_name
       and revision.package_version = skill.package_version
       and revision.name = skill.name
      where skill.package_name = #{packageName} and skill.package_version = #{packageVersion}
      order by skill.name asc
      """)
  List<CurrentSkillDO> listCurrentSkillsByPackage(
      @Param("packageName") String packageName, @Param("packageVersion") String packageVersion);

  @ResultMap("currentSkillResultMap")
  @Select(
      """
      select skill.name, skill.package_name, skill.package_version,
             revision.description, revision.content_revision, revision.content
      from skill
      join skill_revision as revision
        on revision.package_name = skill.package_name
       and revision.package_version = skill.package_version
       and revision.name = skill.name
      where skill.name = #{name}
      """)
  CurrentSkillDO getCurrentSkill(@Param("name") String name);

  @ResultMap("currentSkillResultMap")
  @Select(
      """
      <script>
      select skill.name, skill.package_name, skill.package_version,
             revision.description, revision.content_revision, revision.content
      from skill
      join skill_revision as revision
        on revision.package_name = skill.package_name
       and revision.package_version = skill.package_version
       and revision.name = skill.name
      where skill.name in
      <foreach item="name" collection="names" open="(" separator="," close=")">
          #{name}
      </foreach>
      order by skill.name asc
      for update of skill
      </script>
      """)
  List<CurrentSkillDO> lockCurrentSkillsByNames(@Param("names") Collection<String> names);

  @Insert(
      """
      <script>
      insert into skill (
          name, package_name, package_version
      ) values
      <foreach item="skill" collection="skills" separator=",">
          (#{skill.name}, #{skill.packageName}, #{skill.packageVersion})
      </foreach>
      </script>
      """)
  int insertCurrentSkills(@Param("skills") List<CurrentSkillDO> skills);

  @Delete("delete from skill where package_name = #{packageName}")
  int deleteCurrentSkillsByPackage(@Param("packageName") String packageName);
}
