package fun.fengwk.kkstudio.core.harness.interaction.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/** Narrow Thread owner mapper; every use locks the Thread before an owned Invocation. */
@Mapper
public interface InteractionOwnerThreadMapper extends BaseMapper {
  @Select("select execution_epoch from harness_thread where id = #{threadId} for update")
  Long findExecutionEpochForUpdate(@Param("threadId") long threadId);

  @Update(
      """
      update harness_thread
      set runnable = #{runnable}, updated_at = greatest(updated_at, #{updatedAt})
      where id = #{threadId}
      """)
  int setRunnable(
      @Param("threadId") long threadId,
      @Param("runnable") boolean runnable,
      @Param("updatedAt") OffsetDateTime updatedAt);
}
