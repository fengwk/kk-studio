alter table harness_tool_invocation
    drop constraint ck_harness_tool_invocation_status;

alter table harness_tool_invocation
    add constraint ck_harness_tool_invocation_status check (
        status in (
            'WAITING_APPROVAL',
            'WAITING_INPUT',
            'WAITING_CHILD',
            'READY',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'UNKNOWN'
        )
    );

drop index idx_harness_tool_invocation_thread_nonterminal;

create index idx_harness_tool_invocation_thread_nonterminal
    on harness_tool_invocation (thread_id)
    where status in ('WAITING_APPROVAL', 'WAITING_INPUT', 'WAITING_CHILD', 'READY', 'RUNNING');

comment on index idx_harness_tool_invocation_thread_nonterminal is '未收尾 ToolInvocation 按公共 Thread 归属的查找路径（已终态行在 batch apply 后物理删除，不需要覆盖）';

comment on table harness_tool_invocation is 'ToolInvocation：一次 tool 调用的 durable 生命周期记录，按 (thread_id, turn_start_entry_id, assistant_entry_id, call_index) 对齐；WAITING_INPUT 等待人输入，WAITING_CHILD 等待已接受 task 的 Join，batch apply 后行被物理删除';
comment on column harness_tool_invocation.status is '生命周期状态（含 WAITING_APPROVAL、WAITING_INPUT 与 WAITING_CHILD）';
