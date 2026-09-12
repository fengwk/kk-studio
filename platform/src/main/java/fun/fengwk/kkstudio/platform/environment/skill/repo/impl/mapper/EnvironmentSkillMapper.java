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

import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model.EnvironmentSkillDO;

import java.util.List;
import java.util.UUID;

/**
 * {@code environment_skill} 的原子 SQL 入口。
 *
 * <p>Skill 身份是 {@code (source_id, name)}，因此"原子替换某来源的 inventory"就是先按来源删除再整批插入：两次操作在同一个
 * 事务内完成，读到的是替换前或替换后的完整快照，不存在半更新的中间态。
 */
@Mapper
public interface EnvironmentSkillMapper extends BaseMapper {

  String COLUMNS =
      "environment_id, source_id, name, source_version, description, base_directory, "
          + "content_revision, discovered_at";

  @Results(
      id = "environmentSkillResultMap",
      value = {
        @Result(column = "environment_id", property = "environmentId"),
        @Result(column = "source_id", property = "sourceId"),
        @Result(column = "name", property = "name"),
        @Result(column = "source_version", property = "sourceVersion"),
        @Result(column = "description", property = "description"),
        @Result(column = "base_directory", property = "baseDirectory"),
        @Result(column = "content_revision", property = "contentRevision"),
        @Result(column = "discovered_at", property = "discoveredAt")
      })
  @Select(
      "select "
          + COLUMNS
          + " from environment_skill where environment_id = #{environmentId}"
          + " order by source_id asc, name asc")
  List<EnvironmentSkillDO> listByEnvironment(@Param("environmentId") UUID environmentId);

  /**
   * 列出当前可用于新规划的 Skill 行。
   *
   * <p>可用性由三层事实共同定义：来源状态 READY、{@code applied_version = version}（最新配置已被成功应用），以及行自身的 {@code
   * source_version = version}（该行确实来自当前配置）。任何一层的陈旧行仍是可展示的历史事实，但不构成规划候选。
   */
  @ResultMap("environmentSkillResultMap")
  @Select(
      """
      select skill.environment_id, skill.source_id, skill.name, skill.source_version,
             skill.description, skill.base_directory, skill.content_revision, skill.discovered_at
      from environment_skill as skill
      join environment_skill_source as source
        on source.environment_id = skill.environment_id
       and source.source_id = skill.source_id
      where skill.environment_id = #{environmentId}
        and source.status = 'READY'
        and source.applied_version = source.version
        and skill.source_version = source.version
      order by skill.source_id asc, skill.name asc
      """)
  List<EnvironmentSkillDO> listUsableByEnvironment(@Param("environmentId") UUID environmentId);

  @Insert(
      """
      <script>
      insert into environment_skill (
          environment_id, source_id, name, source_version,
          description, base_directory, content_revision, discovered_at
      ) values
      <foreach item="skill" collection="skills" separator=",">
          (#{skill.environmentId}, #{skill.sourceId}, #{skill.name}, #{skill.sourceVersion},
           #{skill.description}, #{skill.baseDirectory}, #{skill.contentRevision},
           #{skill.discoveredAt})
      </foreach>
      </script>
      """)
  int insertAll(@Param("skills") List<EnvironmentSkillDO> skills);

  @Delete(
      "delete from environment_skill where environment_id = #{environmentId} and source_id = #{sourceId}")
  int deleteBySource(@Param("environmentId") UUID environmentId, @Param("sourceId") UUID sourceId);

  @Delete("delete from environment_skill where environment_id = #{environmentId}")
  int deleteByEnvironment(@Param("environmentId") UUID environmentId);
}
