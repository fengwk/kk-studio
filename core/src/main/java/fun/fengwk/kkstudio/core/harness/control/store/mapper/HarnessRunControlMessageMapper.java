package fun.fengwk.kkstudio.core.harness.control.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.control.store.model.HarnessRunControlMessageDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** harness_run_control_message 行级 MyBatis 映射；状态 CAS 全部以 {@code status='PENDING'} 为前置条件。 */
@Mapper
public interface HarnessRunControlMessageMapper extends BaseMapper {

  String COLUMNS =
      "id, session_id, run_id, control_kind, consumption_mode, message_json, status, "
          + "consumed_run_id, consumed_entry_id, gmt_create as create_time, consumed_at, "
          + "gmt_modified as update_time";

  @Insert(
      """
      insert into harness_run_control_message (
          id, session_id, run_id, control_kind, consumption_mode, message_json,
          status, consumed_run_id, consumed_entry_id,
          gmt_create, consumed_at, gmt_modified
      ) values (
          #{id}, #{sessionId}, #{runId}, #{controlKind}, #{consumptionMode}, #{messageJson},
          #{status}, #{consumedRunId}, #{consumedEntryId},
          #{createTime}, #{consumedAt}, #{updateTime}
      )
      """)
  int insert(HarnessRunControlMessageDO row);

  @Select("select " + COLUMNS + " from harness_run_control_message where id = #{controlId}")
  @Results(
      id = "harnessRunControlMessageResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "control_kind", property = "controlKind"),
        @Result(column = "consumption_mode", property = "consumptionMode"),
        @Result(column = "message_json", property = "messageJson"),
        @Result(column = "status", property = "status"),
        @Result(column = "consumed_run_id", property = "consumedRunId"),
        @Result(column = "consumed_entry_id", property = "consumedEntryId"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "consumed_at", property = "consumedAt"),
        @Result(column = "update_time", property = "updateTime")
      })
  HarnessRunControlMessageDO find(@Param("controlId") long controlId);

  /**
   * 按 run+kind 拉 PENDING；按 id asc。可由调用方包裹 {@code for update} 语义。 run_id 为 null 时（如 FOLLOW_UP 尚未挂到
   * run）此查询不会命中。
   */
  @Select(
      "select "
          + COLUMNS
          + " from harness_run_control_message"
          + " where run_id = #{runId} and control_kind = #{kind} and status = 'PENDING'"
          + " order by id asc")
  @ResultMap("harnessRunControlMessageResultMap")
  List<HarnessRunControlMessageDO> listPendingByRun(
      @Param("runId") long runId, @Param("kind") String kind);

  /** 按 session 拉所有 PENDING；按 id asc。 */
  @Select(
      "select "
          + COLUMNS
          + " from harness_run_control_message"
          + " where session_id = #{sessionId} and status = 'PENDING'"
          + " order by id asc")
  @ResultMap("harnessRunControlMessageResultMap")
  List<HarnessRunControlMessageDO> listPendingBySession(@Param("sessionId") long sessionId);

  /** CAS：PENDING -> CONSUMED；同时写 consumedRunId/consumedEntryId/consumedAt。 */
  @Update(
      """
      update harness_run_control_message
      set status = 'CONSUMED', consumed_run_id = #{consumedRunId},
          consumed_entry_id = #{consumedEntryId}, consumed_at = #{now},
          gmt_modified = #{now}
      where id = #{controlId} and status = 'PENDING'
      """)
  int markConsumed(
      @Param("controlId") long controlId,
      @Param("consumedRunId") long consumedRunId,
      @Param("consumedEntryId") long consumedEntryId,
      @Param("now") LocalDateTime now);

  /** CAS：PENDING -> PROMOTED；consumed_run_id 写入目标 run（直接 promotion 时即新 run 的 id）。 */
  @Update(
      """
      update harness_run_control_message
      set status = 'PROMOTED', consumed_run_id = #{consumedRunId},
          consumed_entry_id = #{consumedEntryId}, consumed_at = #{now},
          gmt_modified = #{now}
      where id = #{controlId} and status = 'PENDING'
      """)
  int markPromoted(
      @Param("controlId") long controlId,
      @Param("consumedRunId") long consumedRunId,
      @Param("consumedEntryId") long consumedEntryId,
      @Param("now") LocalDateTime now);

  /** CAS：PENDING -> CLEARED；只写 consumedAt，不伪造 consumed ids。 */
  @Update(
      """
      update harness_run_control_message
      set status = 'CLEARED', consumed_at = #{now}, gmt_modified = #{now}
      where id = #{controlId} and status = 'PENDING'
      """)
  int markCleared(@Param("controlId") long controlId, @Param("now") LocalDateTime now);

  /** 批量清空指定 run 的 PENDING；返回受影响行数。 */
  @Update(
      """
      update harness_run_control_message
      set status = 'CLEARED', consumed_at = #{now}, gmt_modified = #{now}
      where run_id = #{runId} and status = 'PENDING'
      """)
  int clearPendingByRun(@Param("runId") long runId, @Param("now") LocalDateTime now);

  /** 批量清空指定 session 的所有 PENDING（含 run_id is null）；返回受影响行数。 */
  @Update(
      """
      update harness_run_control_message
      set status = 'CLEARED', consumed_at = #{now}, gmt_modified = #{now}
      where session_id = #{sessionId} and status = 'PENDING'
      """)
  int clearPendingBySession(@Param("sessionId") long sessionId, @Param("now") LocalDateTime now);
}
