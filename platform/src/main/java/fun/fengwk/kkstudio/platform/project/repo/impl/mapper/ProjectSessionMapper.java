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

import fun.fengwk.kkstudio.platform.project.repo.impl.model.ProjectSessionDO;

import java.util.UUID;

@Mapper
public interface ProjectSessionMapper extends BaseMapper {

  @Insert(
      """
      insert into project_session (project_id, session_id, created_at)
      values (#{projectId}, #{sessionId}, clock_timestamp())
      """)
  int insert(@Param("projectId") UUID projectId, @Param("sessionId") UUID sessionId);

  @Select(
      "select project_id, session_id, created_at from project_session where project_id = #{projectId}")
  @Results(
      id = "projectSessionResultMap",
      value = {
        @Result(column = "project_id", property = "projectId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "created_at", property = "createdAt")
      })
  ProjectSessionDO findByProjectId(@Param("projectId") UUID projectId);

  @Select(
      "select project_id, session_id, created_at from project_session where session_id = #{sessionId}")
  @ResultMap("projectSessionResultMap")
  ProjectSessionDO findBySessionId(@Param("sessionId") UUID sessionId);

  @Delete("delete from project_session where project_id = #{projectId}")
  int deleteByProjectId(@Param("projectId") UUID projectId);
}
