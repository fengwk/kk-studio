-- Cloud File System schema baseline.
--
-- Provides virtual hierarchical filesystem nodes and immutable text revision history.
-- Root is virtual (represented by parent_id IS NULL for top-level entries).
-- Node kinds: DIRECTORY, TEXT, BLOB.
-- TEXT nodes store current and historical UTF-8 revisions in cloud_text_revision.
-- BLOB nodes reference storage_blob with RESTRICT foreign key.
-- Pre-seeds system directories: /knowledge, /uploads, /.artifacts, /.artifacts/tool-results.

create table cloud_node (
    id          uuid           primary key,
    parent_id   uuid,
    name        varchar(255)   not null,
    kind        varchar(16)    not null,
    version     bigint         not null default 0,
    blob_id     uuid,
    created_at  timestamptz(3) not null default current_timestamp,
    updated_at  timestamptz(3) not null default current_timestamp,
    constraint fk_cloud_node_parent foreign key (parent_id)
        references cloud_node (id) on delete restrict,
    constraint fk_cloud_node_blob foreign key (blob_id)
        references storage_blob (id) on delete restrict,
    constraint ck_cloud_node_kind check (kind in ('DIRECTORY', 'TEXT', 'BLOB')),
    constraint ck_cloud_node_version_nonneg check (version >= 0),
    constraint ck_cloud_node_blob check (
        (kind = 'BLOB' and blob_id is not null)
        or (kind in ('DIRECTORY', 'TEXT') and blob_id is null)
    ),
    constraint ck_cloud_node_name check (
        btrim(name) <> ''
        and position('/' in name) = 0
        and name !~ '[\r\n\t\x00-\x1f\x7f]'
        and name not in ('.', '..')
    ),
    constraint uk_cloud_node_parent_name unique nulls not distinct (parent_id, name)
);

comment on table cloud_node is 'Cloud File System 虚拟节点：包含 DIRECTORY、TEXT、BLOB 三种节点类型，root 为虚拟目录（顶层节点 parent_id 为 NULL）';
comment on column cloud_node.id is '节点唯一 UUID（应用生成）';
comment on column cloud_node.parent_id is '父目录节点 UUID；NULL 表示根目录 / 下的直接子节点';
comment on column cloud_node.name is '单一路径分段名称（严格 NFC，UTF-8 <= 255 字节，不可包含 / 或控制字符，不可为 . 或 ..）';
comment on column cloud_node.kind is '节点类型：DIRECTORY（目录）、TEXT（带版本控制的 UTF-8 文本）、BLOB（不可变二进制或大文件）';
comment on column cloud_node.version is '节点元数据 CAS 乐观锁版本：非负，从 0 开始，每次重命名/移动递增';
comment on column cloud_node.blob_id is 'BLOB 类型对应的 storage_blob UUID；DIRECTORY 与 TEXT 必须为 NULL';
comment on column cloud_node.created_at is '创建时间（毫秒精度）';
comment on column cloud_node.updated_at is '更新时间（毫秒精度，应用侧维护）';

create index idx_cloud_node_parent
    on cloud_node (parent_id);

create index idx_cloud_node_blob
    on cloud_node (blob_id)
    where blob_id is not null;

create table cloud_text_revision (
    node_id     uuid           not null,
    revision    bigint         not null,
    content     text           not null,
    size_bytes  bigint         not null,
    sha256      char(64)       not null,
    is_current  boolean        not null,
    created_at  timestamptz(3) not null default current_timestamp,
    constraint pk_cloud_text_revision primary key (node_id, revision),
    constraint fk_cloud_text_revision_node foreign key (node_id)
        references cloud_node (id) on delete restrict,
    constraint ck_cloud_text_revision_revision_positive check (revision > 0),
    constraint ck_cloud_text_revision_size_nonneg check (size_bytes >= 0),
    constraint ck_cloud_text_revision_sha256 check (sha256 ~ '^[0-9a-f]{64}$')
);

comment on table cloud_text_revision is 'Cloud File System 文本版本历史：记录 TEXT 节点的不可变 UTF-8 内容历史与当前版本标记';
comment on column cloud_text_revision.node_id is '关联的 TEXT 节点 UUID';
comment on column cloud_text_revision.revision is '版本号：单调递增正整数，从 1 开始';
comment on column cloud_text_revision.content is '权威 UTF-8 文本正文（最大 1 MiB）';
comment on column cloud_text_revision.size_bytes is '文本 UTF-8 严格字节大小（非负）';
comment on column cloud_text_revision.sha256 is '文本 UTF-8 字节内容的 SHA-256 小写十六进制摘要（64 字符）';
comment on column cloud_text_revision.is_current is '是否为该节点的当前活跃版本：每个 TEXT 节点至多一条为 true';
comment on column cloud_text_revision.created_at is '版本创建时间（毫秒精度）';

create unique index uk_cloud_text_revision_current
    on cloud_text_revision (node_id)
    where is_current = true;

-- NOTIFY 触发器：变更提交后向 cloud_files_changed 通道发送受影响 node_id 提示
create or replace function cloud_files_changed_notify()
returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        perform pg_notify('cloud_files_changed', old.id::text);
    else
        perform pg_notify('cloud_files_changed', new.id::text);
    end if;
    return null;
end $$;

create trigger trg_cloud_node_changed_notify
    after insert or update or delete on cloud_node
    for each row execute function cloud_files_changed_notify();

-- 预建系统目录：/knowledge, /uploads, /.artifacts, /.artifacts/tool-results
insert into cloud_node (id, parent_id, name, kind, version, blob_id, created_at, updated_at)
values
    ('c0000000-0000-0000-0000-000000000001', null, 'knowledge', 'DIRECTORY', 0, null, current_timestamp, current_timestamp),
    ('c0000000-0000-0000-0000-000000000002', null, 'uploads', 'DIRECTORY', 0, null, current_timestamp, current_timestamp),
    ('c0000000-0000-0000-0000-000000000003', null, '.artifacts', 'DIRECTORY', 0, null, current_timestamp, current_timestamp),
    ('c0000000-0000-0000-0000-000000000004', 'c0000000-0000-0000-0000-000000000003', 'tool-results', 'DIRECTORY', 0, null, current_timestamp, current_timestamp);
