-- Project/Issue facts notify browser-facing listeners only after transaction commit.
-- The payload is a refetch hint, never an event log.
create or replace function project_issue_changed_notify()
returns trigger as $$
declare
    target_project_id uuid;
    target_issue_id uuid;
    target_run_id uuid;
    target_session_id uuid;
begin
    if tg_table_name = 'project' then
        if tg_op = 'DELETE' then
            target_project_id := old.id;
        else
            target_project_id := new.id;
        end if;
    elsif tg_table_name in ('project_session', 'issue', 'issue_dependency') then
        if tg_op = 'DELETE' then
            target_project_id := old.project_id;
        else
            target_project_id := new.project_id;
        end if;
    elsif tg_table_name in ('issue_input', 'issue_run') then
        if tg_op = 'DELETE' then
            target_issue_id := old.issue_id;
        else
            target_issue_id := new.issue_id;
        end if;
        select project_id into target_project_id
        from issue
        where id = target_issue_id;
    elsif tg_table_name = 'issue_run_session' then
        if tg_op = 'DELETE' then
            target_run_id := old.run_id;
        else
            target_run_id := new.run_id;
        end if;
        select i.project_id into target_project_id
        from issue_run r
        join issue i on i.id = r.issue_id
        where r.id = target_run_id;
    elsif tg_table_name = 'harness_thread' then
        if tg_op = 'DELETE' then
            target_session_id := old.session_id;
        else
            target_session_id := new.session_id;
        end if;
        select project_id into target_project_id
        from project_session
        where session_id = target_session_id;

        if target_project_id is null then
            select i.project_id into target_project_id
            from issue_run_session rs
            join issue_run r on r.id = rs.run_id
            join issue i on i.id = r.issue_id
            where rs.session_id = target_session_id;
        end if;
    end if;

    if target_project_id is not null then
        perform pg_notify('project_issue_changed', target_project_id::text);
    end if;
    return null;
end;
$$ language plpgsql;

create trigger trg_project_issue_changed_project
    after insert or update or delete on project
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_project_session
    after insert or update or delete on project_session
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_issue
    after insert or update or delete on issue
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_dependency
    after insert or update or delete on issue_dependency
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_input
    after insert or update or delete on issue_input
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_run
    after insert or update or delete on issue_run
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_run_session
    after insert or update or delete on issue_run_session
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_thread
    after insert or update or delete on harness_thread
    for each row execute function project_issue_changed_notify();
