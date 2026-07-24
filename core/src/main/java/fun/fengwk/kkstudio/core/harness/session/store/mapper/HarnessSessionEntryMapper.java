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

/** final {@code harness_entry} mapper。 */
@Mapper
public interface HarnessSessionEntryMapper extends BaseMapper {
  String COLUMNS =
      "id, session_id, parent_entry_id, entry_type, payload::text as payload_json, created_at";

  @Insert(
      """
      insert into harness_entry (
          id, session_id, parent_entry_id, entry_type, payload, created_at
      ) values (
          #{id}, #{sessionId}, #{parentEntryId}, #{entryType},
          cast(#{payloadJson} as jsonb), #{createdAt}
      )
      """)
  int insert(HarnessSessionEntryDO entry);

  @Select(
      "select "
          + COLUMNS
          + " from harness_entry where session_id = #{sessionId} and id = #{entryId}")
  @Results(
      id = "harnessSessionEntryResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "created_at", property = "createdAt")
      })
  HarnessSessionEntryDO find(@Param("sessionId") long sessionId, @Param("entryId") long entryId);

  @Select(
      "select "
          + COLUMNS
          + " from harness_entry where session_id = #{sessionId}"
          + " and ((#{parentEntryId} is null and parent_entry_id is null)"
          + " or parent_entry_id = #{parentEntryId}) order by id asc")
  @ResultMap("harnessSessionEntryResultMap")
  List<HarnessSessionEntryDO> listChildren(
      @Param("sessionId") long sessionId, @Param("parentEntryId") Long parentEntryId);

  @Select(
      """
      with recursive path as (
        select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, 0 as depth
        from harness_entry e
        where e.session_id = #{sessionId} and e.id = #{leafEntryId}
        union all
        select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, p.depth + 1
        from harness_entry e
        join path p on p.parent_entry_id = e.id and p.session_id = e.session_id
      )
      select id, session_id, parent_entry_id, entry_type, payload::text as payload_json, created_at
      from path
      order by depth desc
      """)
  @ResultMap("harnessSessionEntryResultMap")
  List<HarnessSessionEntryDO> loadPath(
      @Param("sessionId") long sessionId, @Param("leafEntryId") long leafEntryId);
}
