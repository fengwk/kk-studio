package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** {@code session_owner.issue_agent_session_id} 归属边的 SQL 入口。 */
@Mapper
public interface IssueAgentSessionOwnershipMapper extends BaseMapper {

  @Insert(
      "insert into session_owner (session_id, issue_agent_session_id)"
          + " values (#{sessionId}, #{issueAgentSessionId})")
  int insert(
      @Param("sessionId") UUID sessionId, @Param("issueAgentSessionId") UUID issueAgentSessionId);

  @Select(
      "select issue_agent_session_id from session_owner"
          + " where session_id = #{sessionId} and issue_agent_session_id is not null")
  UUID findAgentSessionIdBySessionId(@Param("sessionId") UUID sessionId);

  @Select(
      "select session_id from session_owner where issue_agent_session_id = #{issueAgentSessionId}"
          + " order by created_at desc, session_id desc")
  List<UUID> listSessionIds(@Param("issueAgentSessionId") UUID issueAgentSessionId);

  @Delete(
      "delete from session_owner"
          + " where session_id = #{sessionId} and issue_agent_session_id is not null")
  int deleteBySessionId(@Param("sessionId") UUID sessionId);
}
