package fun.fengwk.kkstudio.core.harness.session.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HarnessSessionEntryMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_session_entry (
          id, session_id, parent_entry_id, run_id, entry_type, payload_json, gmt_create
      ) values (
          #{id}, #{sessionId}, #{parentEntryId}, #{runId}, #{entryType}, #{payloadJson}, #{createTime}
      )
      """)
  int insert(HarnessSessionEntryDO entry);

  @Select(
      """
      select id, session_id, parent_entry_id, run_id, entry_type, payload_json, gmt_create as create_time
      from harness_session_entry
      where session_id = #{sessionId} and id = #{entryId}
      """)
  @Results(
      id = "harnessSessionEntryResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "create_time", property = "createTime")
      })
  HarnessSessionEntryDO find(@Param("sessionId") long sessionId, @Param("entryId") long entryId);

  @Select(
      """
      select id, session_id, parent_entry_id, run_id, entry_type, payload_json, gmt_create as create_time
      from harness_session_entry
      where session_id = #{sessionId}
        and ((#{parentEntryId} is null and parent_entry_id is null)
             or parent_entry_id = #{parentEntryId})
      order by id asc
      """)
  @ResultMap("harnessSessionEntryResultMap")
  List<HarnessSessionEntryDO> listChildren(
      @Param("sessionId") long sessionId, @Param("parentEntryId") Long parentEntryId);
}
