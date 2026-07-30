package fun.fengwk.kkstudio.core.ai.runtime.realtime.stream.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.ai.runtime.realtime.stream.HarnessRealtimeStreamPolicyDO;

/** 单例 Harness realtime stream policy 的最小持久化映射。 */
@Mapper
public interface HarnessRealtimeStreamPolicyMapper extends BaseMapper {

  @Select(
      """
      select id,
             max_length as maxLength
      from harness_realtime_stream_policy
      where id = 1
      """)
  HarnessRealtimeStreamPolicyDO find();

  /** 单例 id=1 的原子 upsert。仅支持 max_length。 */
  @Insert(
      """
      insert into harness_realtime_stream_policy (
          id, max_length
      ) values (
          1, #{maxLength}
      )
      on conflict (id) do update set
          max_length = excluded.max_length
      """)
  int upsert(@Param("maxLength") long maxLength);
}
