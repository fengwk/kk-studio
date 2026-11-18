-- Migrate only the v2 pricing forms whose meaning is provable. Unknown shapes
-- abort the versioned migration, rolling back every row and Flyway history update.
do $$
declare
    model_row record;
    pricing jsonb;
    multiplier jsonb;
    conditions jsonb;
    outer_time jsonb;
    branches jsonb;
    branch jsonb;
    first_window jsonb;
    second_window jsonb;
    weekdays int[];
    weekday_value jsonb;
    weekday_number integer;
    factor_value text;
    base_entry record;
    ttl_entry record;
    rate_value text;
    ttl_count integer;
    start_minutes integer;
    end_minutes integer;
    intersection_start integer;
    intersection_end integer;
    migrated_rules jsonb;
    config_after jsonb;
begin
    create temporary table migration_time_windows (
        start_minute integer not null,
        end_minute integer not null,
        weekdays integer[] not null
    ) on commit drop;

    for model_row in
        select name, config
          from model
         where kind = 'MODEL'
           and config ? 'pricing'
           and config -> 'pricing' <> 'null'::jsonb
         order by name
    loop
        pricing := model_row.config -> 'pricing';
        if jsonb_typeof(pricing) is distinct from 'object' then
            raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
        end if;
        if pricing - 'base' - 'overrides' - 'multipliers' <> '{}'::jsonb
           or not (pricing ? 'base' and pricing ? 'overrides' and pricing ? 'multipliers')
           or jsonb_typeof(pricing -> 'base') is distinct from 'object'
           or (pricing -> 'base') - 'input' - 'output' - 'cacheRead' - 'cacheWrite' - 'cacheWriteByTtl' - 'reasoning' <> '{}'::jsonb
           or jsonb_typeof(pricing -> 'overrides') is distinct from 'array'
           or jsonb_typeof(pricing -> 'multipliers') is distinct from 'array' then
            raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
        end if;
        if jsonb_array_length(pricing -> 'overrides') <> 0
           or jsonb_array_length(pricing -> 'multipliers') > 1 then
            raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
        end if;

        for base_entry in select key, value from jsonb_each(pricing -> 'base')
        loop
            if base_entry.key in ('input', 'output', 'cacheRead', 'cacheWrite', 'reasoning') then
                if base_entry.key = 'reasoning' and jsonb_typeof(base_entry.value) = 'null' then
                    continue;
                end if;
                if jsonb_typeof(base_entry.value) is distinct from 'string' then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;
                rate_value := base_entry.value #>> '{}';
                if rate_value !~ '^(0|[1-9][0-9]*)([.][0-9]+)?$'
                   or length(split_part(rate_value, '.', 1))
                        + length(split_part(rate_value, '.', 2)) > 64
                   or length(split_part(rate_value, '.', 2)) > 18 then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;
            elsif base_entry.key = 'cacheWriteByTtl' then
                if jsonb_typeof(base_entry.value) is distinct from 'object' then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;
                ttl_count := 0;
                for ttl_entry in select key, value from jsonb_each(base_entry.value)
                loop
                    ttl_count := ttl_count + 1;
                    if ttl_count > 128 then
                        raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                    end if;
                    if ttl_entry.key !~ '^[1-9][0-9]*$'
                       or length(ttl_entry.key) > 19 then
                        raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                    end if;
                    if ttl_entry.key::numeric > 9223372036854775807
                       or jsonb_typeof(ttl_entry.value) is distinct from 'string' then
                        raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                    end if;
                    rate_value := ttl_entry.value #>> '{}';
                    if rate_value !~ '^(0|[1-9][0-9]*)([.][0-9]+)?$'
                       or length(split_part(rate_value, '.', 1))
                            + length(split_part(rate_value, '.', 2)) > 64
                       or length(split_part(rate_value, '.', 2)) > 18 then
                        raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                    end if;
                end loop;
            end if;
        end loop;

        migrated_rules := '[]'::jsonb;
        if jsonb_array_length(pricing -> 'multipliers') = 1 then
            multiplier := pricing -> 'multipliers' -> 0;
            if jsonb_typeof(multiplier) is distinct from 'object'
               or multiplier - 'id' - 'when' - 'factors' <> '{}'::jsonb
               or not (multiplier ? 'id' and multiplier ? 'when' and multiplier ? 'factors')
               or jsonb_typeof(multiplier -> 'id') is distinct from 'string'
               or jsonb_typeof(multiplier -> 'factors') is distinct from 'object'
               or (multiplier -> 'factors') - 'input' - 'output' - 'cacheRead' - 'cacheWrite' - 'reasoning' <> '{}'::jsonb
               or not ((multiplier -> 'factors') ? 'input'
                   and (multiplier -> 'factors') ? 'output'
                   and (multiplier -> 'factors') ? 'cacheRead'
                   and (multiplier -> 'factors') ? 'cacheWrite'
                   and (multiplier -> 'factors') ? 'reasoning') then
                raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
            end if;

            factor_value := multiplier -> 'factors' ->> 'input';
            if jsonb_typeof(multiplier -> 'factors' -> 'input') is distinct from 'string'
               or jsonb_typeof(multiplier -> 'factors' -> 'output') is distinct from 'string'
               or jsonb_typeof(multiplier -> 'factors' -> 'cacheRead') is distinct from 'string'
               or jsonb_typeof(multiplier -> 'factors' -> 'cacheWrite') is distinct from 'string'
               or jsonb_typeof(multiplier -> 'factors' -> 'reasoning') is distinct from 'string'
               or multiplier -> 'factors' ->> 'output' <> factor_value
               or multiplier -> 'factors' ->> 'cacheRead' <> factor_value
               or multiplier -> 'factors' ->> 'cacheWrite' <> factor_value
               or multiplier -> 'factors' ->> 'reasoning' <> factor_value
               or factor_value !~ '^(0|[1-9][0-9]*)([.][0-9]+)?$'
               or length(split_part(factor_value, '.', 1))
                    + length(split_part(factor_value, '.', 2)) > 64
               or length(split_part(factor_value, '.', 2)) > 18 then
                raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
            end if;

            conditions := multiplier -> 'when';
            if jsonb_typeof(conditions) is distinct from 'object'
               or conditions - 'time' - 'any' <> '{}'::jsonb
               or not (conditions ? 'time' and conditions ? 'any')
               or jsonb_typeof(conditions -> 'time') is distinct from 'object'
               or jsonb_typeof(conditions -> 'any') is distinct from 'array' then
                raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
            end if;
            if jsonb_array_length(conditions -> 'any') < 1
               or jsonb_array_length(conditions -> 'any') > 64 then
                raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
            end if;

            outer_time := conditions -> 'time';
            if outer_time - 'start' - 'end' - 'timezone' - 'weekdays' <> '{}'::jsonb
               or not (outer_time ? 'start' and outer_time ? 'end'
                   and outer_time ? 'timezone' and outer_time ? 'weekdays')
               or jsonb_typeof(outer_time -> 'start') is distinct from 'string'
               or jsonb_typeof(outer_time -> 'end') is distinct from 'string'
               or jsonb_typeof(outer_time -> 'timezone') is distinct from 'string'
               or outer_time ->> 'timezone' <> 'UTC'
               or jsonb_typeof(outer_time -> 'weekdays') is distinct from 'array'
               or outer_time ->> 'start' !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
               or outer_time ->> 'end' !~ '^(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)$' then
                raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
            end if;
            start_minutes := split_part(outer_time ->> 'start', ':', 1)::integer * 60
                + split_part(outer_time ->> 'start', ':', 2)::integer;
            end_minutes := case when outer_time ->> 'end' = '24:00' then 1440
                else split_part(outer_time ->> 'end', ':', 1)::integer * 60
                    + split_part(outer_time ->> 'end', ':', 2)::integer end;
            if start_minutes >= end_minutes then
                raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
            end if;

            weekdays := array[]::integer[];
            for weekday_value in
                select value from jsonb_array_elements(outer_time -> 'weekdays') as item(value)
            loop
                if jsonb_typeof(weekday_value) is distinct from 'number'
                   or weekday_value::text !~ '^[1-7]$' then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;
                weekday_number := weekday_value::text::integer;
                if weekday_number = any(weekdays) then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;
                weekdays := array_append(weekdays, weekday_number);
            end loop;
            if cardinality(weekdays) = 0 then
                raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
            end if;
            select array_agg(day order by day) into weekdays from unnest(weekdays) as item(day);

            branches := conditions -> 'any';
            truncate migration_time_windows;
            for branch in select value from jsonb_array_elements(branches) as item(value)
            loop
                if jsonb_typeof(branch) is distinct from 'object'
                   or branch - 'all' <> '{}'::jsonb
                   or not (branch ? 'all')
                   or jsonb_typeof(branch -> 'all') is distinct from 'array' then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;
                if jsonb_array_length(branch -> 'all') <> 2 then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;

                first_window := branch -> 'all' -> 0 -> 'time';
                second_window := branch -> 'all' -> 1 -> 'time';
                if jsonb_typeof(branch -> 'all' -> 0) is distinct from 'object'
                   or jsonb_typeof(branch -> 'all' -> 1) is distinct from 'object'
                   or (branch -> 'all' -> 0) - 'time' <> '{}'::jsonb
                   or (branch -> 'all' -> 1) - 'time' <> '{}'::jsonb
                   or jsonb_typeof(first_window) is distinct from 'object'
                   or jsonb_typeof(second_window) is distinct from 'object' then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;
                if first_window - 'start' - 'end' - 'timezone' <> '{}'::jsonb
                   or second_window - 'start' - 'end' - 'timezone' <> '{}'::jsonb
                   or not (first_window ? 'start' and first_window ? 'end' and first_window ? 'timezone')
                   or not (second_window ? 'start' and second_window ? 'end' and second_window ? 'timezone')
                   or jsonb_typeof(first_window -> 'start') is distinct from 'string'
                   or jsonb_typeof(first_window -> 'end') is distinct from 'string'
                   or jsonb_typeof(first_window -> 'timezone') is distinct from 'string'
                   or jsonb_typeof(second_window -> 'start') is distinct from 'string'
                   or jsonb_typeof(second_window -> 'end') is distinct from 'string'
                   or jsonb_typeof(second_window -> 'timezone') is distinct from 'string'
                   or first_window ->> 'timezone' <> 'UTC'
                   or second_window ->> 'timezone' <> 'UTC'
                   or first_window ->> 'start' !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
                   or second_window ->> 'start' !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
                   or first_window ->> 'end' !~ '^(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)$'
                   or second_window ->> 'end' !~ '^(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)$'
                   or (first_window ->> 'start') collate "C" >= (first_window ->> 'end') collate "C"
                   or (second_window ->> 'start') collate "C" >= (second_window ->> 'end') collate "C" then
                    raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
                end if;

                intersection_start := greatest(
                    start_minutes,
                    split_part(first_window ->> 'start', ':', 1)::integer * 60
                        + split_part(first_window ->> 'start', ':', 2)::integer,
                    split_part(second_window ->> 'start', ':', 1)::integer * 60
                        + split_part(second_window ->> 'start', ':', 2)::integer);
                intersection_end := least(
                    end_minutes,
                    case when first_window ->> 'end' = '24:00' then 1440
                        else split_part(first_window ->> 'end', ':', 1)::integer * 60
                            + split_part(first_window ->> 'end', ':', 2)::integer end,
                    case when second_window ->> 'end' = '24:00' then 1440
                        else split_part(second_window ->> 'end', ':', 1)::integer * 60
                            + split_part(second_window ->> 'end', ':', 2)::integer end);
                if intersection_start < intersection_end then
                    insert into migration_time_windows values (intersection_start, intersection_end, weekdays);
                end if;
            end loop;

            if not exists (select 1 from migration_time_windows)
               or exists (
                   select 1
                     from migration_time_windows first_rule
                     join migration_time_windows second_rule
                       on first_rule.ctid < second_rule.ctid
                      and first_rule.weekdays && second_rule.weekdays
                      and first_rule.start_minute < second_rule.end_minute
                      and second_rule.start_minute < first_rule.end_minute) then
                raise exception 'unsupported legacy model pricing configuration; no pricing data changed';
            end if;

            select jsonb_agg(
                       jsonb_build_object(
                           'type', 'time_multiplier',
                           'start', lpad((window_row.start_minute / 60)::text, 2, '0') || ':'
                               || lpad((window_row.start_minute % 60)::text, 2, '0'),
                           'end', case when window_row.end_minute = 1440 then '24:00'
                               else lpad((window_row.end_minute / 60)::text, 2, '0') || ':'
                                   || lpad((window_row.end_minute % 60)::text, 2, '0') end,
                           'timezone', 'UTC',
                           'weekdays', to_jsonb(window_row.weekdays),
                           'factor', factor_value)
                       order by window_row.start_minute, window_row.end_minute)
              into migrated_rules
              from migration_time_windows window_row;
        end if;

        config_after := jsonb_set(
            model_row.config,
            '{pricing}',
            jsonb_build_object(
                'base', pricing -> 'base',
                'rules', migrated_rules,
                'changes', '[]'::jsonb),
            false);
        update model set config = config_after where name = model_row.name;
    end loop;
end $$;
