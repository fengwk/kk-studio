package fun.fengwk.kkstudio.core.harness.session.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;

import java.util.List;

@Mapper
public interface HarnessSessionEntryMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_session_entry (
          id, session_id, parent_entry_id, entry_type, payload_json, gmt_create
      ) values (
          #{id}, #{sessionId}, #{parentEntryId}, #{entryType}, #{payloadJson}, #{createTime}
      )
      """)
  int insert(HarnessSessionEntryDO entry);

  @Select(
      """
      select id, session_id, parent_entry_id, entry_type, payload_json, gmt_create as create_time
      from harness_session_entry
      where session_id = #{sessionId} and id = #{entryId}
      """)
  @Results(
      id = "harnessSessionEntryResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "create_time", property = "createTime")
      })
  HarnessSessionEntryDO find(@Param("sessionId") long sessionId, @Param("entryId") long entryId);

  @Select(
      """
      select id, session_id, parent_entry_id, entry_type, payload_json, gmt_create as create_time
      from harness_session_entry
      where id = #{entryId}
      """)
  @ResultMap("harnessSessionEntryResultMap")
  HarnessSessionEntryDO findById(@Param("entryId") long entryId);

  @Select(
      """
      select id, session_id, parent_entry_id, entry_type, payload_json, gmt_create as create_time
      from harness_session_entry
      where session_id = #{sessionId}
        and ((#{parentEntryId} is null and parent_entry_id is null)
             or parent_entry_id = #{parentEntryId})
      order by id asc
      """)
  @ResultMap("harnessSessionEntryResultMap")
  List<HarnessSessionEntryDO> listChildren(
      @Param("sessionId") long sessionId, @Param("parentEntryId") Long parentEntryId);

  @Select(
      "select id, session_id, parent_entry_id, entry_type, payload_json, "
          + "gmt_create as create_time from harness_session_entry where session_id = #{sessionId} "
          + "and entry_type = #{entryType} order by id desc limit 1")
  @ResultMap("harnessSessionEntryResultMap")
  HarnessSessionEntryDO findLatestByType(
      @Param("sessionId") long sessionId, @Param("entryType") String entryType);

  @Select(
      "with recursive path (id, parent_entry_id, path_depth) as (select id, parent_entry_id, 0"
          + " from harness_session_entry where session_id = #{sessionId} and id = #{leafEntryId}"
          + " union all select entry.id, entry.parent_entry_id, path.path_depth + 1 from"
          + " harness_session_entry entry join path on entry.id = path.parent_entry_id where"
          + " entry.session_id = #{sessionId}) select entry.id, entry.session_id,"
          + " entry.parent_entry_id, entry.entry_type, entry.payload_json,"
          + " entry.gmt_create as create_time from harness_session_entry entry join path on"
          + " path.id = entry.id where entry.entry_type = #{entryType} order by path.path_depth"
          + " asc limit 1")
  @ResultMap("harnessSessionEntryResultMap")
  HarnessSessionEntryDO findLatestOnPathByType(
      @Param("sessionId") long sessionId,
      @Param("leafEntryId") long leafEntryId,
      @Param("entryType") String entryType);

  @Select(
      "select id, session_id, parent_entry_id, entry_type, payload_json, "
          + "gmt_create as create_time from harness_session_entry where session_id = #{sessionId} "
          + "order by id")
  @ResultMap("harnessSessionEntryResultMap")
  List<HarnessSessionEntryDO> listBySession(@Param("sessionId") long sessionId);
}
