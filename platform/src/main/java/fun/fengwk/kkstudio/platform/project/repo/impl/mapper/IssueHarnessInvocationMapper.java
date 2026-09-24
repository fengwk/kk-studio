package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface IssueHarnessInvocationMapper extends BaseMapper {

  /**
   * 该 Project 的 Issue Agent 工作 Branch 上是否存在未收尾的 Model/Tool 调用。
   *
   * <p>只读 harness 执行表中的生命周期事实，不依赖 IssueRun 是否已进入终态：Run 结束后残留的 DISPATCHING/RUNNING
   * 调用仍然可能继续产生外部副作用。模型侧按 {@code uk_harness_model_invocation_turn} 的 thread_id 前缀定位， 工具侧按 {@code
   * idx_harness_tool_invocation_model_nonterminal} 的未收尾部分索引定位。
   */
  @Select(
      """
      select exists(
          select 1
          from project_issue i
          join project_issue_agent_session s on s.issue_id = i.id
          where i.project_id = #{projectId}
            and (
              exists(
                  select 1
                  from harness_model_invocation m
                  where m.thread_id = s.thread_id
                    and m.status in ('READY', 'DISPATCHING', 'RUNNING'))
              or exists(
                  select 1
                  from harness_tool_invocation t
                  join harness_model_invocation m on m.id = t.model_invocation_id
                  where m.thread_id = s.thread_id
                    and t.status in ('WAITING_APPROVAL', 'READY', 'DISPATCHING', 'RUNNING'))))
      """)
  boolean hasNonTerminalByProjectId(@Param("projectId") UUID projectId);
}
