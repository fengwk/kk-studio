package fun.fengwk.kkstudio.core.harness.model.worker;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/**
 * 适配器专用的 {@code harness_thread} 窄视图 mapper，只覆盖 {@link PostgresqlModelInvocationTransactions} 实际需要的
 * {@code id/runnable/execution_epoch}。不复用仍绑定 legacy {@code gmt_*}/{@code status}/{@code version} 列的
 * {@code HarnessThreadMapper}。
 *
 * <p>任何 {@code markRunnable} 路径都用 {@code greatest(updated_at, #{now})} 写入，确保 {@code updated_at}
 * 在并发场景下永不倒退。
 */
@Mapper
public interface HarnessModelInvocationThreadMapper extends BaseMapper {

  String COLUMNS = """
      t.id, t.runnable, t.execution_epoch
      """;

  @Select("select " + COLUMNS + " from harness_thread t where t.id = #{threadId} for update")
  @Results(
      id = "harnessModelInvocationThreadResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "runnable", property = "runnable"),
        @Result(column = "execution_epoch", property = "executionEpoch")
      })
  HarnessModelInvocationThreadDO findForUpdate(@Param("threadId") long threadId);

  /**
   * 把 owning Thread 标为 runnable；要求当前 Thread 的 execution_epoch 与 invocation.execution_epoch
   * 完全一致。updated_at 单调。
   */
  @Update(
      """
      update harness_thread
      set runnable = true,
          updated_at = greatest(updated_at, #{now})
      where id = #{threadId}
        and execution_epoch = #{executionEpoch}
      """)
  int markRunnable(
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("now") OffsetDateTime now);
}
