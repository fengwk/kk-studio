-- 运输层探针：source 与 target 使用完全相同的列布局（不匹配源数据在 target 上被真实即时约束拒绝）。
create table legacy_copy_probe (
    id uuid primary key,
    parent_id uuid,
    unique_key text,
    json_payload jsonb,
    blob_payload bytea,
    tags text[],
    observed_at timestamptz(3),
    note text
);

create table legacy_copy_probe_parent (
    id uuid primary key,
    label text not null
);

-- self-FK 探针：两端都启用即时外键，用于证明单语句 COPY 的「语句末校验」语义。
create table legacy_copy_probe_self (
    id uuid primary key,
    parent_id uuid,
    constraint fk_legacy_copy_probe_self_parent foreign key (parent_id)
        references legacy_copy_probe_self (id)
);
