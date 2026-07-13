package fun.fengwk.kkstudio.core.harness.session.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
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
          id, entry_id, session_id, parent_entry_id, entry_type, payload_json, gmt_create, gmt_modified, version
      ) values (
          #{id}, #{entryId}, #{sessionId}, #{parentEntryId}, #{entryType}, #{payloadJson},
          #{createTime}, #{createTime}, 0
      )
      """)
  int insert(HarnessSessionEntryDO entry);

  @Select(
      """
      select id, entry_id, session_id, parent_entry_id, entry_type, payload_json, gmt_create as create_time
      from harness_session_entry
      where session_id = #{sessionId} and entry_id = #{entryId}
      """)
  @Results(
      id = "harnessSessionEntryResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "entry_id", property = "entryId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "create_time", property = "createTime")
      })
  HarnessSessionEntryDO find(
      @Param("sessionId") String sessionId, @Param("entryId") String entryId);
}
