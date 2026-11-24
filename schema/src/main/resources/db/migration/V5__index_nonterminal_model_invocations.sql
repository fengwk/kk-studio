create index idx_builtin_model_invocation_thread_nonterminal
    on builtin_model_invocation (thread_id)
    where status in ('READY', 'RUNNING');
