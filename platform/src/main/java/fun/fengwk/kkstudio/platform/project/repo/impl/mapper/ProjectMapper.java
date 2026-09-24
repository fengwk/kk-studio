package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.project.repo.impl.model.ProjectDO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ProjectMapper extends BaseMapper {

  String COLUMNS =
      "id, title, description, yolo_enabled, max_review_rejections, next_issue_number, "
          + "version, archived_at, created_at, updated_at";

  @Insert(
      """
      insert into project (
          id, title, description, yolo_enabled, max_review_rejections, next_issue_number,
          version, archived_at, created_at, updated_at
      ) values (
          #{id}, #{title}, #{description}, #{yoloEnabled}, #{maxReviewRejections}, 1,
          0, null, clock_timestamp(), clock_timestamp()
      )
      """)
  int insert(ProjectDO project);

  @Select("select " + COLUMNS + " from project where id = #{id}")
  @Results(
      id = "projectResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "description", property = "description"),
        @Result(column = "yolo_enabled", property = "yoloEnabled"),
        @Result(column = "max_review_rejections", property = "maxReviewRejections"),
        @Result(column = "next_issue_number", property = "nextIssueNumber"),
        @Result(column = "version", property = "version"),
        @Result(column = "archived_at", property = "archivedAt"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  ProjectDO getById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from project where id = #{id} for update")
  @ResultMap("projectResultMap")
  ProjectDO lockById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from project where id = #{id} for share")
  @ResultMap("projectResultMap")
  ProjectDO lockForShare(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from project where id = #{id} for key share")
  @ResultMap("projectResultMap")
  ProjectDO lockForKeyShare(@Param("id") UUID id);

  @Update(
      """
      update project
      set title = #{project.title},
          description = #{project.description},
          yolo_enabled = #{project.yoloEnabled},
          max_review_rejections = #{project.maxReviewRejections},
          updated_at = clock_timestamp(),
          version = version + 1
      where id = #{project.id} and version = #{expectedVersion}
      """)
  int updateById(
      @Param("project") ProjectDO project, @Param("expectedVersion") long expectedVersion);

  @Update(
      """
      update project
      set archived_at = #{archivedAt},
          updated_at = clock_timestamp(),
          version = version + 1
      where id = #{id} and version = #{expectedVersion}
      """)
  int updateArchivedAt(
      @Param("id") UUID id,
      @Param("archivedAt") Instant archivedAt,
      @Param("expectedVersion") long expectedVersion);

  @Select(
      """
      update project
      set next_issue_number = next_issue_number + 1,
          updated_at = clock_timestamp()
      where id = #{projectId}
      returning next_issue_number - 1
      """)
  Long allocateNextIssueNumber(@Param("projectId") UUID projectId);

  @Select("select " + COLUMNS + " from project order by updated_at desc, created_at desc")
  @ResultMap("projectResultMap")
  List<ProjectDO> listAll();

  @Select(
      """
      select id, title, description, yolo_enabled, max_review_rejections, next_issue_number,
             version, archived_at, created_at, updated_at
      from project
      where (archived_at is not null) = #{archived}
      order by updated_at desc, created_at desc
      """)
  @ResultMap("projectResultMap")
  List<ProjectDO> listByArchived(@Param("archived") boolean archived);

  @Delete("delete from project where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
