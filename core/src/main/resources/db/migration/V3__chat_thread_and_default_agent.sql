-- Chat default Agent is required for every persisted Chat. Existing rows are
-- backfilled from the lowest current Agent definition before the constraint is
-- installed. Seed locations run before this migration, while baseline-only
-- databases with no Chat rows remain valid without a catalog seed.
do $$
declare
    fallback_agent_id bigint;
begin
    if exists (select 1 from chat where default_agent_id is null) then
        select min(id) into fallback_agent_id from agent_definition;
        if fallback_agent_id is null then
            raise exception
                'cannot backfill chat.default_agent_id because no agent_definition exists';
        end if;
        update chat
        set default_agent_id = fallback_agent_id
        where default_agent_id is null;
    end if;
end
$$;

alter table chat
    alter column default_agent_id set not null;

create table chat_thread (
    chat_id     bigint        not null,
    thread_id   bigint        not null,
    created_at  timestamptz(3) not null default current_timestamp,
    constraint pk_chat_thread primary key (chat_id, thread_id),
    constraint fk_chat_thread_chat foreign key (chat_id)
        references chat (id) on delete cascade,
    constraint fk_chat_thread_thread foreign key (thread_id)
        references harness_thread (id) on delete cascade
);

create index idx_chat_thread_thread
    on chat_thread (thread_id, chat_id);

create index idx_harness_thread_updated_id
    on harness_thread (updated_at desc, id desc);

create index idx_harness_thread_created_id
    on harness_thread (created_at desc, id desc);
