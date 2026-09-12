-- 独立 model 身份：agent_model 新增不可变逻辑身份之外的 wire 模型标识。
--
-- `name` 与 `provider_name` 共同构成 Catalog 内的不可变逻辑资源身份（branch settings / agent_definition / API 路径使用），
-- `model_id` 是发往上游 Provider 的真实模型标识。二者相互独立且可以不相等，`model_id` 不参与唯一性约束。
--
-- 已有 Anthropic 数据若使用旧 `provider.config.modelAliases`，先把 alias 提取为 `model_id`；其余数据以 `name` 填充。
-- 随后删除 provider 侧第二映射源，确保运行时只有 `agent_model.model_id` 一个事实源。
--
-- Variant 同步收敛为 `{id, reasoningEffort?}`。旧 `none` 映射为显式 `off`，旧 `minimal` / `xhigh` / `max`
-- 映射到当前协议支持的最近档位；未知 effort 直接阻止迁移，不静默丢失语义。

alter table agent_model
    add column model_id varchar(256) not null default '';

update agent_model as model
set model_id = coalesce(
    nullif(provider.config -> 'modelAliases' ->> model.name, ''),
    model.name
)
from agent_provider as provider
where provider.name = model.provider_name
  and model.model_id = '';

update agent_provider
set config = config - 'modelAliases'
where config ? 'modelAliases';

alter table agent_model
    alter column model_id drop default;

alter table agent_model
    add constraint ck_agent_model_model_id check (
        model_id !~ '^[[:space:]]'
        and model_id !~ '[[:space:]]$'
        and char_length(model_id) > 0
    );

do $$
begin
    if exists (
        select 1
        from agent_model as model
        cross join lateral jsonb_array_elements(
            case
                when jsonb_typeof(model.config -> 'variants') = 'array'
                    then model.config -> 'variants'
                else '[]'::jsonb
            end
        ) as variant
        where jsonb_typeof(model.config -> 'variants') = 'array'
          and nullif(btrim(variant ->> 'reasoningEffort'), '') is not null
          and lower(btrim(variant ->> 'reasoningEffort')) not in (
              'high', 'medium', 'low', 'off', 'none', 'minimal', 'xhigh', 'max'
          )
    ) then
        raise exception 'agent_model config contains unsupported reasoningEffort';
    end if;
end
$$;

update agent_model as model
set config = jsonb_set(
    model.config,
    '{variants}',
    (
        select coalesce(
            jsonb_agg(
                jsonb_strip_nulls(
                    jsonb_build_object(
                        'id', variant.value -> 'id',
                        'reasoningEffort',
                        case
                            when nullif(
                                btrim(variant.value ->> 'reasoningEffort'),
                                ''
                            ) is null then null
                            when lower(btrim(variant.value ->> 'reasoningEffort')) = 'none'
                                then 'off'
                            when lower(btrim(variant.value ->> 'reasoningEffort')) = 'minimal'
                                then 'low'
                            when lower(btrim(variant.value ->> 'reasoningEffort')) in ('xhigh', 'max')
                                then 'high'
                            else lower(btrim(variant.value ->> 'reasoningEffort'))
                        end
                    )
                )
                order by variant.ordinality
            ),
            '[]'::jsonb
        )
        from jsonb_array_elements(
            case
                when jsonb_typeof(model.config -> 'variants') = 'array'
                    then model.config -> 'variants'
                else '[]'::jsonb
            end
        )
            with ordinality as variant(value, ordinality)
    )
)
where jsonb_typeof(model.config -> 'variants') = 'array';

comment on column agent_model.model_id is
    '发往上游 Provider 的真实 wire 模型标识：非空白、无环绕空白、≤256；与逻辑身份 (provider_name, name) 独立且不唯一';
