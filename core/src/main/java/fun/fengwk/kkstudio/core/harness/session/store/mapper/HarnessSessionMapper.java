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

/** final {@code harness_session} mapper。 */
@Mapper
public interface HarnessSessionMapper extends BaseMapper {
  String COLUMNS =
      "id, title, main_thread_id, parent_session_id, parent_invocation_id, created_at, updated_at";

  @Insert(
      """
      insert into harness_session (
          id, title, main_thread_id, parent_session_id, parent_invocation_id,
          created_at, updated_at
      ) values (
          #{id}, #{title}, #{mainThreadId}, #{parentSessionId}, #{parentInvocationId},
          #{createdAt}, #{updatedAt}
      )
      """)
  int insert(HarnessSessionDO session);

  @Select("select " + COLUMNS + " from harness_session where id = #{sessionId}")
  @Results(
      id = "harnessSessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "main_thread_id", property = "mainThreadId"),
        @Result(column = "parent_session_id", property = "parentSessionId"),
        @Result(column = "parent_invocation_id", property = "parentInvocationId"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  HarnessSessionDO find(@Param("sessionId") long sessionId);

  @Select(
      "select "
          + COLUMNS
          + " from harness_session where parent_session_id is null"
          + " order by updated_at desc, id desc")
  @ResultMap("harnessSessionResultMap")
  List<HarnessSessionDO> listRoots();

  @Select(
      """
      with recursive ancestors as (
        select id, parent_session_id, 0 as depth
        from harness_session
        where id = #{sessionId}
        union all
        select s.id, s.parent_session_id, a.depth + 1
        from harness_session s
        join ancestors a on s.id = a.parent_session_id
      )
      select id from ancestors where parent_session_id is null order by depth desc limit 1
      """)
  Long findRootSessionId(@Param("sessionId") long sessionId);

  @Select(
      """
      with recursive ancestors as (
        select id, parent_session_id, 0 as depth
        from harness_session
        where id = #{sessionId}
        union all
        select s.id, s.parent_session_id, a.depth + 1
        from harness_session s
        join ancestors a on s.id = a.parent_session_id
      )
      select coalesce(max(depth), 0) from ancestors
      """)
  int findDepth(@Param("sessionId") long sessionId);
}
