package fun.fengwk.kkstudio.core.harness.run.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunEventDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HarnessRunEventMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_run_event (id, run_id, sequence, event_type, payload_json, gmt_create)
      values (#{id}, #{runId}, #{sequence}, #{eventType}, #{payloadJson}, #{createTime})
      """)
  int insert(HarnessRunEventDO event);

  @Select(
      """
      select id, run_id, sequence, event_type, payload_json, gmt_create as create_time
      from harness_run_event
      where run_id = #{runId} and sequence > #{afterSequence}
      order by sequence asc
      limit #{limit}
      """)
  @Results(
      id = "harnessRunEventResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "event_type", property = "eventType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "create_time", property = "createTime")
      })
  List<HarnessRunEventDO> listAfter(
      @Param("runId") long runId,
      @Param("afterSequence") long afterSequence,
      @Param("limit") int limit);

  @Select(
      "select e.id, e.run_id, r.session_id, e.sequence, e.event_type, e.payload_json, e.gmt_create"
          + " as create_time from harness_run_event e join harness_run r on r.id = e.run_id join"
          + " harness_session s on s.id = r.session_id where s.root_session_id = #{rootSessionId}"
          + " and e.id > #{afterEventId} order by e.id asc"
          + " limit #{limit}")
  @ResultMap("harnessRunEventResultMap")
  List<HarnessRunEventDO> listRootActivity(
      @Param("rootSessionId") long rootSessionId,
      @Param("afterEventId") long afterEventId,
      @Param("limit") int limit);

  @Select("select max(gmt_create) from harness_run_event where run_id = #{runId}")
  LocalDateTime latestActivityAt(@Param("runId") long runId);
}
