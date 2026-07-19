package fun.fengwk.kkstudio.core.harness.thread.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadStopDO;

@Mapper
public interface HarnessThreadStopMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_thread_stop (
          id, thread_id, client_request_id, gmt_create
      ) values (
          #{id}, #{threadId}, #{clientRequestId}, #{createTime}
      )
      """)
  int insert(HarnessThreadStopDO stop);

  @Select(
      """
      select id, thread_id, client_request_id, gmt_create as create_time
      from harness_thread_stop
      where id = #{stopId}
      """)
  @Results(
      id = "harnessThreadStopResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "client_request_id", property = "clientRequestId"),
        @Result(column = "create_time", property = "createTime")
      })
  HarnessThreadStopDO find(@Param("stopId") long stopId);

  @Select(
      """
      select id, thread_id, client_request_id, gmt_create as create_time
      from harness_thread_stop
      where thread_id = #{threadId} and client_request_id = #{clientRequestId}
      """)
  @ResultMap("harnessThreadStopResultMap")
  HarnessThreadStopDO findByClientRequestId(
      @Param("threadId") long threadId, @Param("clientRequestId") String clientRequestId);
}
