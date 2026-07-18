package fun.fengwk.kkstudio.core.harness.session.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;

import java.util.List;

@Mapper
public interface HarnessSessionMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_session (
          id, agent_definition_id, title,
          parent_session_id, root_session_id, parent_invocation_id, depth,
          gmt_create, gmt_modified, version
      ) values (
          #{id}, #{agentDefinitionId}, #{title},
          #{parentSessionId}, #{rootSessionId}, #{parentInvocationId}, #{depth},
          #{createTime}, #{updateTime}, #{version}
      )
      """)
  int insert(HarnessSessionDO session);

  @Select(
      """
      select id, agent_definition_id, title,
             parent_session_id, root_session_id, parent_invocation_id, depth,
             version, gmt_create as create_time, gmt_modified as update_time
      from harness_session
      where id = #{sessionId}
      """)
  @Results(
      id = "harnessSessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "agent_definition_id", property = "agentDefinitionId"),
        @Result(column = "title", property = "title"),
        @Result(column = "parent_session_id", property = "parentSessionId"),
        @Result(column = "root_session_id", property = "rootSessionId"),
        @Result(column = "parent_invocation_id", property = "parentInvocationId"),
        @Result(column = "depth", property = "depth"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  HarnessSessionDO find(@Param("sessionId") long sessionId);

  @Select(
      """
      select id, agent_definition_id, title,
             parent_session_id, root_session_id, parent_invocation_id, depth,
             version, gmt_create as create_time, gmt_modified as update_time
      from harness_session
      where id = #{sessionId}
      for update
      """)
  @ResultMap("harnessSessionResultMap")
  HarnessSessionDO findForUpdate(@Param("sessionId") long sessionId);

  @Select(
      """
      select id, agent_definition_id, title,
             parent_session_id, root_session_id, parent_invocation_id, depth,
             version, gmt_create as create_time, gmt_modified as update_time
      from harness_session
      where parent_session_id is null
      order by gmt_modified desc, id desc
      """)
  @ResultMap("harnessSessionResultMap")
  List<HarnessSessionDO> listRoots();

  @Select(
      """
      select id, agent_definition_id, title,
             parent_session_id, root_session_id, parent_invocation_id, depth,
             version, gmt_create as create_time, gmt_modified as update_time
      from harness_session
      where root_session_id = #{rootSessionId} and id <> #{rootSessionId}
      order by id asc
      """)
  @ResultMap("harnessSessionResultMap")
  List<HarnessSessionDO> listByRoot(@Param("rootSessionId") long rootSessionId);
}
