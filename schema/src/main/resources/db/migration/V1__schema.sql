-- kk-studio PostgreSQL baseline schema for Flyway V1.
-- All ids are application-allocated UUIDs; timestamps use millisecond precision timestamptz(3).
-- Tables are grouped by owning module; blob content lives only in object storage.

-- 1. Shared foundation: global blob storage

create table storage_blob (
    id            uuid           primary key,
    sha256        char(64)       not null,
    size_bytes    bigint         not null,
    media_type    varchar(256)   not null,
    width         integer,
    height        integer,
    duration_ms   bigint,
    archive_index jsonb,
    ref_count     bigint         not null default 0,
    state         varchar(16)    not null default 'ACTIVE',
    created_at    timestamptz(3) not null default current_timestamp,
    updated_at    timestamptz(3) not null default current_timestamp,
    constraint ck_storage_blob_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_storage_blob_size_nonneg check (size_bytes >= 0),
    constraint ck_storage_blob_media_type_nonblank check (btrim(media_type) <> ''),
    constraint ck_storage_blob_dimensions_pair check ((width is null) = (height is null)),
    constraint ck_storage_blob_dimensions_positive check (
        width is null or (width > 0 and height > 0)
    ),
    constraint ck_storage_blob_duration_positive check (duration_ms is null or duration_ms > 0),
    constraint ck_storage_blob_archive_index check (
        archive_index is null or jsonb_typeof(archive_index) = 'object'
    ),
    constraint ck_storage_blob_state_ref_count check (
        (state = 'ACTIVE' and ref_count > 0) or (state = 'DELETING' and ref_count = 0)
    )
);

create unique index uk_storage_blob_active_hash
    on storage_blob (sha256, size_bytes)
    where state = 'ACTIVE';

comment on column storage_blob.archive_index is '服务端校验过的 ZIP 归档条目索引（{entries:[{path,size,dataOffset,compressedSize,crc32}]}）；null 表示不是服务端生成的归档';

create table storage_upload (
    id                  uuid           primary key,
    owner_user_id       uuid,
    candidate_blob_id   uuid           not null,
    blob_id             uuid,
    filename            varchar(512)   not null,
    declared_media_type varchar(256)   not null,
    declared_size       bigint         not null,
    declared_sha256     char(64)       not null,
    image_format        varchar(32),
    original_width      integer,
    original_height     integer,
    stored_width        integer,
    stored_height       integer,
    cleanup_requested_at timestamptz(3),
    cleanup_token       uuid,
    cleanup_until       timestamptz(3),
    created_at          timestamptz(3) not null default current_timestamp,
    constraint uk_storage_upload_candidate unique (candidate_blob_id),
    constraint ck_storage_upload_filename_nonblank check (btrim(filename) <> ''),
    constraint ck_storage_upload_media_type_nonblank check (btrim(declared_media_type) <> ''),
    constraint ck_storage_upload_size_nonneg check (declared_size >= 0),
    constraint ck_storage_upload_sha256 check (declared_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_storage_upload_image_metadata_pair check (
        (image_format is null and original_width is null and original_height is null
            and stored_width is null and stored_height is null)
        or (image_format is not null and original_width is not null and original_height is not null
            and stored_width is not null and stored_height is not null)
    ),
    constraint ck_storage_upload_image_dimensions check (
        original_width is null or (original_width > 0 and original_height > 0
            and stored_width > 0 and stored_height > 0)
    ),
    constraint ck_storage_upload_image_format check (
        image_format is null or (image_format ~ '^[a-z0-9][a-z0-9.+-]{0,31}$'
            and declared_media_type = 'image/' || image_format)
    ),
    constraint ck_storage_upload_cleanup_pair check (
        (cleanup_token is null) = (cleanup_until is null)
    ),
    constraint fk_storage_upload_blob foreign key (blob_id)
        references storage_blob (id) on delete restrict
);

create index idx_storage_upload_cleanup_claim
    on storage_upload (cleanup_requested_at, created_at, cleanup_until, id);

create table storage_image_model_view (
    source_blob_id  uuid         not null,
    image_tier      varchar(16)  not null,
    view_blob_id    uuid         not null,
    original_width  integer      not null,
    original_height integer      not null,
    display_width   integer      not null,
    display_height  integer      not null,
    constraint pk_storage_image_model_view primary key (source_blob_id, image_tier),
    constraint ck_storage_image_model_view_tier check (image_tier in ('720P', '1080P')),
    constraint ck_storage_image_model_view_dimensions check (
        original_width > 0 and original_height > 0 and display_width > 0 and display_height > 0
    ),
    constraint ck_storage_image_model_view_distinct_blobs check (source_blob_id <> view_blob_id),
    constraint fk_storage_image_model_view_source foreign key (source_blob_id)
        references storage_blob (id) on delete restrict,
    constraint fk_storage_image_model_view_blob foreign key (view_blob_id)
        references storage_blob (id) on delete restrict
);

comment on table storage_image_model_view is 'Durable resized model view for a plugin image, keyed by original blob and image tier; the row owns one reference to view_blob_id and is removed when its source enters DELETING.';

comment on table storage_blob is 'Deduplicated immutable content address of the global blob storage: one ACTIVE row per (sha256, size_bytes) content, with immutable media facts and a counted reference lifecycle ACTIVE -> DELETING -> row removal.';

comment on column storage_blob.id is 'Blob id (uuid): deterministic S3 keys are derived from it (blobs/{id}/original, blobs/{id}/preview.webp), never persisted.';
comment on column storage_blob.sha256 is 'Lowercase hex SHA-256 of the original content (64 chars).';
comment on column storage_blob.size_bytes is 'Original content size in bytes (non-negative).';
comment on column storage_blob.media_type is 'Canonical media type probed at complete time (not the client declaration).';
comment on column storage_blob.width is 'Immutable pixel width probed at complete time; null when not probed.';
comment on column storage_blob.height is 'Immutable pixel height probed at complete time; null when not probed.';
comment on column storage_blob.duration_ms is 'Immutable media duration in milliseconds probed at complete time; null for still media.';
comment on column storage_blob.ref_count is 'Live owner reference count: READY uploads and later application and Harness consumers.';
comment on column storage_blob.state is 'Lifecycle state: ACTIVE (ref_count > 0) or DELETING (terminal, ref_count = 0).';
comment on column storage_blob.created_at is 'Row creation time (timestamptz, millisecond precision).';
comment on column storage_blob.updated_at is 'Application-managed last write time (timestamptz, millisecond precision).';

comment on index uk_storage_blob_active_hash is 'Content dedup: at most one ACTIVE blob row per (sha256, size_bytes); DELETING rows stay outside the key so a new upload of the same content can proceed.';

comment on table storage_upload is 'Per-upload contract: blob_id NULL = PENDING (client PUTs to uploads/{uploadId}/original), non-NULL = READY (blob is retained for this upload). Expiry is created_at + current upload TTL for unclaimed uploads.';

comment on column storage_upload.id is 'Upload id (uuid): the deterministic temp key uploads/{id}/original is derived from it.';
comment on column storage_upload.candidate_blob_id is 'Server-pre-assigned blob id for the PENDING->READY transition (no FK: the blob row is created at complete time).';
comment on column storage_upload.blob_id is 'Referenced blob while READY (FK RESTRICT); NULL while PENDING. The READY upload holds exactly one reference to this blob.';
comment on column storage_upload.filename is 'Client-declared original filename (non-blank, <= 512 chars).';
comment on column storage_upload.declared_media_type is 'Client-declared media type (non-blank, <= 256 chars).';
comment on column storage_upload.declared_size is 'Client-declared content size in bytes (non-negative).';
comment on column storage_upload.declared_sha256 is 'Client-declared lowercase hex SHA-256 (64 chars).';
comment on column storage_upload.cleanup_requested_at is 'Durable explicit cleanup request; NULL for active uploads and retained until maintenance removes the upload row.';
comment on column storage_upload.cleanup_token is 'Cleanup ownership token UUID; NULL when unclaimed and fenced on finalize/release.';
comment on column storage_upload.cleanup_until is 'Cleanup lease deadline paired with cleanup_token; an expired lease is reclaimable.';
comment on column storage_upload.created_at is 'Row creation time (timestamptz, millisecond precision). Unclaimed uploads expire at created_at + current upload TTL; there is no persisted expiry column.';

comment on index uk_storage_upload_candidate is 'Every upload pre-assigns a distinct candidate blob id so PENDING rows can never collide on the future blob identity.';
comment on index idx_storage_upload_cleanup_claim is 'Storage Maintenance claim scan: requested, claimed or expired uploads with absent/expired leases; expiry compares created_at against now minus current TTL.';

create table storage_object_cleanup (
    key              varchar(512)   not null,
    next_attempt_at  timestamptz(3) not null,
    constraint pk_storage_object_cleanup primary key (key),
    constraint ck_storage_object_cleanup_key_nonblank check (btrim(key) <> '')
);

create index idx_storage_object_cleanup_due
    on storage_object_cleanup (next_attempt_at, key);

comment on table storage_object_cleanup is 'Durable object-key tombstone: a key whose last database fact was removed is deleted again whenever a late PUT/COPY recreates it. Records are permanent (never removed by TTL); each sweep claims the next due batch, advances next_attempt_at before doing object I/O and backs off failures.';

comment on column storage_object_cleanup.key is 'Object storage key (uploads/{id}/original, blobs/{id}/original or blobs/{id}/preview.webp); primary key, so enqueueing is idempotent.';
comment on column storage_object_cleanup.next_attempt_at is 'Next sweep deadline: driven by database time so every node claims with the same clock; advanced far on claim and set back on object deletion failure.';

comment on index idx_storage_object_cleanup_due is 'Storage Maintenance tombstone scan: due keys in next_attempt_at order.';

-- 2. Platform kernel storage: accounts, environments, Harness protocol and session blobs

create table studio_user (
    id uuid primary key,
    username varchar(32) not null unique,
    password_hash varchar(256) not null,
    profile jsonb not null default '{}'::jsonb,
    role varchar(16) not null,
    disabled_at timestamptz(3),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    constraint ck_studio_user_username check (username ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    constraint ck_studio_user_profile check (jsonb_typeof(profile) = 'object'),
    constraint ck_studio_user_role check (role in ('ADMIN', 'USER'))
);

alter table storage_upload
    add constraint fk_storage_upload_owner foreign key (owner_user_id)
        references studio_user (id) on delete restrict;

create table login_session (
    token_hash bytea primary key,
    user_id uuid not null references studio_user(id) on delete cascade,
    created_at timestamptz(3) not null default current_timestamp,
    expires_at timestamptz(3) not null
);
create index idx_login_session_user on login_session(user_id);

comment on table studio_user is 'Studio account credentials, profile and authorization state.';
comment on column studio_user.id is 'Application-allocated account UUID.';
comment on column studio_user.username is 'Unique account username (lowercase letters, digits, dots, underscores or hyphens).';
comment on column studio_user.password_hash is 'Spring Security encoded password hash.';
comment on column studio_user.profile is 'Account profile JSON object.';
comment on column studio_user.role is 'Account role: ADMIN or USER.';
comment on column studio_user.disabled_at is 'Account disable time; NULL means enabled.';
comment on column studio_user.created_at is 'Row creation time (timestamptz, millisecond precision).';
comment on column studio_user.updated_at is 'Application-managed last write time (timestamptz, millisecond precision).';
comment on table login_session is 'Hashed bearer-token sessions associated with Studio accounts.';
comment on column login_session.token_hash is 'Hash of the session bearer token; plaintext tokens are not persisted.';
comment on column login_session.user_id is 'Owning Studio account UUID; sessions are removed with the account.';
comment on column login_session.created_at is 'Session creation time (timestamptz, millisecond precision).';
comment on column login_session.expires_at is 'Session expiration time.';
comment on index idx_login_session_user is 'Lookup sessions by owning account.';

-- Precomputed Spring Security PBKDF2 hash for the built-in kkstudio bootstrap credential.
insert into studio_user(id, username, password_hash, role)
values ('00000000-0000-0000-0000-000000000001', 'kkstudio', '{pbkdf2}473d0449576a25f739f57765f5b5d4ddd346ec503ad602aeab8da49810d4c61f460d54a08e070b1552a60ccab0285830', 'ADMIN');

create table environment (
    id                  uuid          primary key,
    name                varchar(64)   not null,
    note                varchar(512),
    registration_token_hash char(64)  not null,
    daemon_config       jsonb         not null,
    install_code_hash   char(64),
    install_code_expires_at timestamptz(3),
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    version             bigint        not null default 0,
    constraint uk_environment_name unique (name),
    constraint uk_environment_registration_token_hash unique (registration_token_hash),
    constraint ck_environment_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
        and position('/' in name) = 0
    ),
    constraint ck_environment_note check (
        note is null or btrim(note) <> ''
    ),
    constraint ck_environment_registration_token_hash check (
        registration_token_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_environment_version_nonneg check (version >= 0),
    constraint ck_environment_daemon_config_object check (
        jsonb_typeof(daemon_config) = 'object'
    ),
    constraint ck_environment_install_code_pair check (
        (install_code_hash is null) = (install_code_expires_at is null)
    ),
    constraint ck_environment_install_code_hash check (
        install_code_hash is null or install_code_hash ~ '^[0-9a-f]{64}$'
    )
);

comment on table environment is '稳定 Environment 卡片注册表：UUID 主键跨重启不变，name 是唯一且不可变的配置标识，daemon_config 是该卡片的完整 Daemon 配置';
comment on column environment.id is 'Environment 的全局唯一 UUID（服务端生成，永不变更）';
comment on column environment.name is 'Environment 唯一名称（非空、无首尾空白、不含斜杠、不超过 64 字符）；创建后不可变';
comment on column environment.note is '卡片备注（最多 512 字符），保存后立即影响展示和提示词，不依赖 Daemon READY';
comment on column environment.registration_token_hash is 'Daemon 握手准入注册凭证的 SHA-256（小写十六进制）；明文只在创建、轮换与安装脚本中出现一次，不落库';
comment on column environment.daemon_config is '该卡片的完整 Daemon 配置（JSON object：studioUrl、bashExecutable、lsp、proxyUrl）；Studio 数据库是唯一权威';
comment on column environment.install_code_hash is '一次性安装 code 的 SHA-256；与 install_code_expires_at 成对出现，兑换或配置变更后清空';
comment on column environment.install_code_expires_at is '安装 code 的数据库时间失效点（固定五分钟）；与 install_code_hash 成对出现';
comment on column environment.created_at is '创建时间（毫秒精度）';
comment on column environment.updated_at is '最后更新时间（毫秒精度），应用侧维护';
comment on column environment.version is 'CAS 乐观锁版本：非负，从 0 开始，每次显式更新 +1；同时是完整配置快照版本';

create index idx_environment_updated
    on environment (updated_at, id);

create table environment_connection (
    environment_id  uuid           primary key,
    owner_node_id   uuid           not null,
    lease_token     uuid           not null,
    status          varchar(32)    not null,
    ready_metadata  jsonb,
    recent_events   jsonb          not null default '[]'::jsonb,
    last_seen_at    timestamptz(3) not null,
    lease_until     timestamptz(3) not null,
    constraint fk_environment_connection_environment foreign key (environment_id)
        references environment (id) on delete cascade,
    constraint ck_environment_connection_status check (
        status in ('CONNECTING', 'READY')
    ),
    constraint ck_environment_connection_ready_metadata check (
        ready_metadata is null or jsonb_typeof(ready_metadata) = 'object'
    ),
    constraint ck_environment_connection_recent_events check (
        jsonb_typeof(recent_events) = 'array'
    ),
    constraint ck_environment_connection_lease check (
        lease_until > last_seen_at
    )
);

comment on table environment_connection is 'Environment 的 daemon 连接租约：每个 Environment 由持有租约的节点独占路由（fencing 见 lease_token）';
comment on column environment_connection.environment_id is 'Environment 的全局唯一 UUID（PK，FK cascade）';
comment on column environment_connection.owner_node_id is '当前持有该连接路由的 App 节点实例 UUID';
comment on column environment_connection.lease_token is '当前路由租约代币 UUID（每次接管/重绑生成新 token，fence 旧持有者）';
comment on column environment_connection.status is '路由状态：CONNECTING / READY';
comment on column environment_connection.ready_metadata is '最近一次被接受的 READY 宿主 metadata（daemonVersion + hostInfo JSON object）；断线或重新 CONNECTING 时保留，从未 READY 时为 null';
comment on column environment_connection.recent_events is '最近 200 条连接运维事件（JSON array，元素只有时间、级别、类型与去敏消息）；不保存 stdout、凭据或签名地址';
comment on column environment_connection.last_seen_at is '最后活跃时间（毫秒精度）';
comment on column environment_connection.lease_until is '租约到期时间（毫秒精度），必须晚于 last_seen_at';

create index idx_environment_connection_lease_until
    on environment_connection (lease_until);

create index idx_environment_connection_owner
    on environment_connection (owner_node_id);

create table cluster_node (
    instance_id  uuid           primary key,
    internal_url varchar(512)   not null,
    lease_until  timestamptz(3) not null,
    last_seen_at timestamptz(3) not null,
    constraint ck_cluster_node_lease check (
        lease_until > last_seen_at
    )
);

comment on table cluster_node is 'App 节点内部 IDE 路由注册表：节点可达 HTTP(S) origin 与心跳租约，只承载控制元数据，不保存环境存活字节';
comment on column cluster_node.instance_id is '节点实例 UUID（PK），与 environment_connection.owner_node_id 对应';
comment on column cluster_node.internal_url is '该节点对外可达的 HTTP(S) origin，仅用于内部单跳转发';
comment on column cluster_node.lease_until is '注册租约到期时间（毫秒精度），过期节点的路由不被选中';
comment on column cluster_node.last_seen_at is '最后一次心跳时间（毫秒精度），必须早于 lease_until';

create table system_setting (
    id         bigint         primary key,
    config     jsonb          not null,
    version    bigint         not null default 0,
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    constraint ck_system_setting_id check (id = 1),
    constraint ck_system_setting_config_object check (jsonb_typeof(config) = 'object'),
    constraint ck_system_setting_version_nonneg check (version >= 0)
);

comment on table system_setting is '全局 system settings 单行聚合（id=1）：config 为强类型 canonical JSON，version 为 CAS 乐观锁版本';
comment on column system_setting.id is '恒为 1：全局配置恰好一行';
comment on column system_setting.config is '六个 section（tool/aiRuntime/environment/backendNetwork/storageMedia/advanced）的完整强类型 canonical JSON，必须为 object';
comment on column system_setting.version is '乐观锁行版本：非负，从 0 开始，每次写操作 +1';
comment on column system_setting.created_at is '创建时间（毫秒精度）';
comment on column system_setting.updated_at is '最后更新时间（毫秒精度），应用侧维护';

insert into system_setting (id, config) values (
    1,
    '{
      "advanced":{"applicationEventHeartbeatIntervalMillis":20000,"applicationEventMaxBytes":2097152,"applicationEventQueueCapacity":512,"applicationEventSendTimeoutMillis":10000,"modelDispatchBusyFallbackDelayMillis":1000,"postgresqlWorkNotificationPollMillis":5000,"postgresqlWorkReconnectBackoffMillis":1000,"processorHeartbeatIntervalMillis":10000,"processorLeaseDurationMillis":30000,"threadResolveFailureDelayMillis":1000,"toolDispatchBusyFallbackDelayMillis":1000,"toolPreflightFailureDelayMillis":1000},
      "aiRuntime":{"compactionKeepRecentTokens":20000,"modelHttpRetryStatusCodes":[408,429,500,502,503,504],"retryBackoffStrategy":"EXPONENTIAL","retryBaseDelayMillis":2000,"retryMaxDelayMillis":60000,"retryMaxRetries":3,"subagentMaxConcurrency":10,"subagentMaxDepth":2,"subagentMaxTotalConcurrency":0,"subagentMaxTurns":50},
      "environment":{"heartbeatTimeoutMillis":60000},
      "backendNetwork":{"noProxyHosts":"localhost,127.*,::1"},
      "storageMedia":{"canvasMediaProcessTimeoutMillis":30000,"imageTier":"720P","maxImagesPerRequest":20,"s3PresignDefaultExpiresSeconds":1800,"s3PresignMaxExpiresSeconds":3600,"temporaryResourceCleanupIntervalSeconds":1800,"temporaryResourceTtlSeconds":259200,"thumbnailMaxDimension":512,"thumbnailQuality":80,"uploadExpiresSeconds":86400},
      "tool":{"maxOutputMib":16,"modelGatewayBusyRetryMillis":5000,"permission":{"bash":[{"action":"ask","pattern":"*"}],"edit":[{"action":"ask","pattern":"*"}],"write":[{"action":"ask","pattern":"*"}]},"toolGatewayOverloadRetryMillis":5000}
    }'::jsonb
);

create table harness_session (
    id uuid primary key,
    name varchar(256) not null,
    created_at timestamptz(3) not null,
    constraint ck_harness_session_name check (
        btrim(name) <> ''
    )
);

comment on table harness_session is 'Session 聚合边界：组织一份 append-only Entry Tree，不拥有 Thread；只记录自身 id、显示名称与创建时间';
comment on column harness_session.id is 'Session 的全局唯一 UUID（创建后不可变）';
comment on column harness_session.name is 'Session 显示名称：应用保证非空、单行且至多 256 个 Unicode 码点，并由应用生成默认名或手动重命名（check 只防御空白串）';
comment on column harness_session.created_at is 'Session 创建时间（毫秒精度，创建后不可变）';

create table harness_entry (
    id uuid primary key,
    session_id uuid not null,
    parent_entry_id uuid,
    entry_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    created_at timestamptz(3) not null,
    entry_seq bigint generated always as identity,
    constraint uk_harness_entry_session_id unique (session_id, id),
    constraint fk_harness_entry_session foreign key (session_id)
        references harness_session (id),
    constraint fk_harness_entry_parent foreign key (session_id, parent_entry_id)
        references harness_entry (session_id, id),
    constraint ck_harness_entry_type check (
        entry_type in (
            'ROOT',
            'SYSTEM',
            'TURN_START',
            'MESSAGE',
            'MODEL_ATTEMPT_FAILURE',
            'NATIVE_COMPACTION',
            'CUSTOM_MESSAGE',
            'ASSISTANT_ERROR',
            'ASSISTANT_ABORTED',
            'COMPACTION',
            'TURN_END',
            'NOTIFICATION',
            'SETTINGS',
            'FORK'
        )
    ),
    constraint ck_harness_entry_parent_shape check (
        (entry_type = 'ROOT' and parent_entry_id is null)
        or (entry_type <> 'ROOT' and parent_entry_id is not null)
    ),
    constraint ck_harness_entry_parent_not_self check (
        parent_entry_id is null or parent_entry_id <> id
    )
);

comment on table harness_entry is '不可变 Entry：append-only 树节点，ROOT 必须先于其他 Entry，parent 链必须连续且同 Session';
comment on column harness_entry.id is 'Entry 的全局唯一 UUID';
comment on column harness_entry.session_id is '所属 Session';
comment on column harness_entry.parent_entry_id is '父 Entry；ROOT 为 null，其余必须非 null 且不能指向自身';
comment on column harness_entry.entry_type is 'Entry 类型（ROOT/SYSTEM/TURN_START/MESSAGE/MODEL_ATTEMPT_FAILURE/NATIVE_COMPACTION/CUSTOM_MESSAGE/ASSISTANT_ERROR/ASSISTANT_ABORTED/COMPACTION/TURN_END/NOTIFICATION/SETTINGS/FORK）';
comment on column harness_entry.payload is '按 entry_type 编码的不可变 payload（JSON object）';
comment on column harness_entry.created_at is 'Entry 创建时间（毫秒精度，不承担排序）';
comment on column harness_entry.entry_seq is '数据库分配的追加序号：同一 Session 内严格递增且父先于子，只用于创建序读取与 keyset 分页';

create unique index uk_harness_entry_single_root
    on harness_entry (session_id)
    where entry_type = 'ROOT';

create index idx_harness_entry_parent
    on harness_entry (session_id, parent_entry_id);

create index idx_harness_entry_session_seq
    on harness_entry (session_id, entry_seq);

comment on index uk_harness_entry_single_root is '每个 Session 至多一个 ROOT Entry';
comment on index idx_harness_entry_parent is 'parent 回溯与同 Session 树遍历索引';
comment on index idx_harness_entry_session_seq is 'Session 内按创建顺序读取与 keyset 分页索引';

create table harness_thread (
    id uuid primary key,
    session_id uuid not null,
    parent_thread_id uuid,
    head_entry_id uuid not null,
    creation_request_hash char(64) not null,
    name varchar(256) not null,
    root_thread_id uuid not null,
    yolo_enabled boolean not null default false,
    kind varchar(24) not null,
    constraints jsonb not null,
    execution_control varchar(16) not null,
    input_through_sequence bigint not null default 0 check (input_through_sequence >= 0),
    next_command_sequence bigint not null check (next_command_sequence >= 1),
    version bigint not null check (version >= 0),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_session foreign key (session_id)
        references harness_session (id),
    constraint fk_harness_thread_parent foreign key (parent_thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_root foreign key (root_thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_head foreign key (session_id, head_entry_id)
        references harness_entry (session_id, id),
    -- 同 Session 复合引用目标：上层表用 (session_id, id) 把
    -- Thread 与 Session 一起冻结，无法配错 Session。
    constraint uk_harness_thread_session unique (session_id, id),
    constraint ck_harness_thread_parent_not_self check (
        parent_thread_id is null or parent_thread_id <> id
    ),
    constraint ck_harness_thread_kind check (
        kind in ('USER', 'SUBAGENT', 'COMPACTION', 'PLUGIN_TASK')
    ),
    constraint ck_harness_thread_constraints check (
        jsonb_typeof(constraints) = 'object'
    ),
    constraint ck_harness_thread_creation_request_hash check (
        creation_request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_name check (
        btrim(name) <> ''
    ),
    constraint ck_harness_thread_execution_control check (
        execution_control in ('RUNNABLE', 'STOPPED')
    ),
    constraint ck_harness_thread_input_through check (
        input_through_sequence < next_command_sequence
    )
);

comment on table harness_thread is 'Thread：指向 head Entry 的游标状态机，version 随每次对外字段变化精确 +1；session_id 与 creation_request_hash 创建后不可变，head 必须与 session 同 Session；parent_thread_id 记录不可变执行父关系，root_thread_id 记录不可变执行根，yolo_enabled 是执行根的 YOLO 开关，kind 与 constraints 是创建时冻结的用途与执行约束，execution_control 区分 RUNNABLE/STOPPED；input_through_sequence 记录已接纳的水位；(session_id, id) 唯一供同 Session 复合引用';
comment on column harness_thread.id is 'Thread 的全局唯一 UUID';
comment on column harness_thread.session_id is '所属 Session（创建后不可变）';
comment on column harness_thread.parent_thread_id is '不可变父 Thread UUID（无父/根 Thread 为 null，禁止指向自身）';
comment on column harness_thread.head_entry_id is '当前 head Entry（必须存在且属于 thread.session_id 的 Session）';
comment on column harness_thread.creation_request_hash is 'NEW_SESSION/NEW_THREAD 初始创建请求指纹：服务端 64 位小写 SHA-256 身份键（创建后不可变，不对产品 DTO 暴露）';
comment on column harness_thread.name is 'Thread 显示名称：应用保证非空、单行且至多 256 个 Unicode 码点，并由应用生成默认名或手动重命名（check 只防御空白串）';
comment on column harness_thread.root_thread_id is '不可变执行根 UUID（根 Thread 指向自身），供树锁与整树读取';
comment on column harness_thread.yolo_enabled is '执行根的 YOLO 开关；子代理恒为 false，有效值取自执行根';
comment on column harness_thread.kind is '不可变 Thread 用途：USER / SUBAGENT / COMPACTION / PLUGIN_TASK';
comment on column harness_thread.constraints is '不可变执行约束 JSON：maxOutputTokens（可空）、allowAutoCompaction；子 Thread 为请求约束与父约束合并后的有效值';
comment on column harness_thread.execution_control is '持久执行控制状态（RUNNABLE/STOPPED）';
comment on column harness_thread.input_through_sequence is '已被普通 INPUT 接纳或确定性拒绝的输入序号水位（>= 0，初值 0，< next_command_sequence）';
comment on column harness_thread.next_command_sequence is '下一条 Command 的 sequence（从 1 递增）';
comment on column harness_thread.version is '并发控制版本：任何对外字段变化必须 +1';
comment on column harness_thread.created_at is 'Thread 创建时间（毫秒精度）';
comment on column harness_thread.updated_at is 'Thread 最后更新时间（毫秒精度）';

create index idx_harness_thread_parent
    on harness_thread (parent_thread_id);

create index idx_harness_thread_session
    on harness_thread (session_id, created_at, id);

create index idx_harness_thread_root
    on harness_thread (root_thread_id);

comment on index idx_harness_thread_parent is 'parent 回溯与子 Thread 树遍历索引';
comment on index idx_harness_thread_root is '按执行根读取整棵执行树';
comment on index idx_harness_thread_session is 'listThreadsBySession 按 (session_id, created_at, id) 读取 Session 的 Thread 列表';

create unique index uk_harness_thread_root_name
    on harness_thread (session_id, name)
    where parent_thread_id is null;

comment on index uk_harness_thread_root_name is '同一 Session 内执行根名称唯一（partial unique：仅约束 parent_thread_id is null 的根行）';

create table realtime_subscription (
    thread_id        uuid           not null,
    node_instance_id uuid           not null,
    lease_until      timestamptz(3) not null,
    constraint pk_realtime_subscription primary key (thread_id, node_instance_id),
    constraint fk_realtime_subscription_thread foreign key (thread_id)
        references harness_thread (id) on delete cascade
);

comment on table realtime_subscription is '跨节点 realtime 订阅租约：按 (Thread, 节点实例) 唯一，随节点心跳续租，Thread 删除时级联清理';
comment on column realtime_subscription.thread_id is '被订阅的 Thread UUID（复合 PK 前缀，FK cascade）';
comment on column realtime_subscription.node_instance_id is '订阅方节点实例 UUID；不设 FK，读取时连接 cluster_node 校验租约';
comment on column realtime_subscription.lease_until is '订阅租约到期时间（毫秒精度），由节点心跳按 45 秒节奏续租';

create index idx_realtime_subscription_node
    on realtime_subscription (node_instance_id);

comment on index idx_realtime_subscription_node is '节点退出时按 node_instance_id 清理本节点全部订阅';

create table harness_thread_command (
    thread_id uuid not null,
    sequence bigint not null check (sequence > 0),
    command_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    idempotency_key uuid not null,
    request_hash char(64) not null,
    applied_entry_id uuid,
    stop_request_id uuid,
    cancelled_at timestamptz(3),
    created_at timestamptz(3) not null,
    primary key (thread_id, sequence),
    constraint fk_harness_thread_command_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_command_applied foreign key (applied_entry_id)
        references harness_entry (id),
    constraint uk_harness_thread_command_idempotency unique (thread_id, idempotency_key),
    constraint ck_harness_thread_command_type check (
        command_type in (
            'USER_MESSAGE',
            'CUSTOM_MESSAGE',
            'SET_EXECUTION',
            'SET_ENVIRONMENT',
            'NOTIFICATION'
        )
    ),
    constraint ck_harness_thread_command_request_hash check (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_command_cancel_pair check (
        (stop_request_id is null and cancelled_at is null)
        or (stop_request_id is not null and cancelled_at is not null)
    )
);

comment on table harness_thread_command is 'ThreadCommand：无代理主键，身份为 (thread_id, sequence)；QUEUED 只能推进为 APPLIED 或 CANCELLED，CANCELLED 必须 stop_request_id 与 cancelled_at 成对';
comment on column harness_thread_command.thread_id is '所属 Thread';
comment on column harness_thread_command.sequence is 'Thread 内单调递增序号（与身份一起构成主键）';
comment on column harness_thread_command.command_type is 'Command payload 类型';
comment on column harness_thread_command.payload is '按 command_type 编码的 payload（JSON object）';
comment on column harness_thread_command.idempotency_key is '客户端幂等键（UUID），同一 Thread 内唯一';
comment on column harness_thread_command.request_hash is '客户端 raw 命令（含 ordered contents 与 uploadId）的 canonical SHA-256（64 小写 hex）；同 idempotencyKey 重放必须精确匹配';
comment on column harness_thread_command.applied_entry_id is 'APPLIED 时应用的 Entry；与 cancelled_at 互斥';
comment on column harness_thread_command.stop_request_id is 'CANCELLED 时取消它的 Stop stopRequestId（queued-only receipt 幂等键）；与 cancelled_at 成对';
comment on column harness_thread_command.cancelled_at is 'CANCELLED 时间';
comment on column harness_thread_command.created_at is 'Command 创建时间（毫秒精度）';

create index idx_harness_thread_command_queued
    on harness_thread_command (thread_id, sequence)
    where applied_entry_id is null and cancelled_at is null;

comment on index idx_harness_thread_command_queued is '按 sequence 升序读取 QUEUED Command（for update 锁序）';

create index idx_harness_thread_command_stop_request
    on harness_thread_command (thread_id, stop_request_id, sequence)
    where stop_request_id is not null;

comment on index idx_harness_thread_command_stop_request is 'Stop 幂等键：(thread_id, stop_request_id) 按 sequence 升序汇总被该 stopRequestId 取消的 Command';

create table harness_tool_invocation (
    id uuid primary key,
    thread_id uuid not null,
    turn_start_entry_id uuid not null,
    assistant_entry_id uuid not null,
    call_index integer not null check (call_index >= 0),
    call jsonb not null check (jsonb_typeof(call) = 'object'),
    binding jsonb check (binding is null or jsonb_typeof(binding) = 'object'),
    status varchar(32) not null,
    attempt integer not null check (attempt >= 0),
    approval jsonb check (approval is null or jsonb_typeof(approval) = 'object'),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    input_receipt jsonb,
    constraint fk_harness_tool_invocation_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_tool_invocation_turn_start foreign key (turn_start_entry_id)
        references harness_entry (id),
    constraint fk_harness_tool_invocation_assistant foreign key (assistant_entry_id)
        references harness_entry (id),
    constraint uk_harness_tool_invocation_call_index unique (assistant_entry_id, call_index),
    constraint ck_harness_tool_invocation_status check (
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
    ),
    constraint ck_harness_tool_invocation_terminal_facts check (
        result is null or error is null
    ),
    constraint ck_harness_tool_input_receipt check (
        input_receipt is null or jsonb_typeof(input_receipt) = 'object'
    )
);

comment on table harness_tool_invocation is 'ToolInvocation：一次 tool 调用的 durable 生命周期记录，按 (thread_id, turn_start_entry_id, assistant_entry_id, call_index) 对齐；WAITING_INPUT 等待人输入，WAITING_CHILD 等待已接受 task 的 Join，batch apply 后行被物理删除';
comment on column harness_tool_invocation.id is 'ToolInvocation 的全局唯一 UUID';
comment on column harness_tool_invocation.thread_id is '公共归属 ThreadId；工具能力从该 Thread 的 turn_start_entry_id 冻结快照读取，不依赖 ModelInvocation';
comment on column harness_tool_invocation.turn_start_entry_id is '公共归属 TurnStart；必须属于 thread_id 且是 assistant_entry_id 所在分支的 open turn';
comment on column harness_tool_invocation.assistant_entry_id is '携带对应 ToolCall 的 Assistant MESSAGE Entry，必须位于同一 Thread/Session 与 Turn 分支';
comment on column harness_tool_invocation.call_index is 'assistant 消息内 tool call 的下标（从 0 递增）';
comment on column harness_tool_invocation.call is '冻结的 ToolCall（JSON object）';
comment on column harness_tool_invocation.binding is '冻结的 tool binding（JSON object，仅在 immediate FAILED attempt=0 槽位可空）';
comment on column harness_tool_invocation.status is '生命周期状态（含 WAITING_APPROVAL、WAITING_INPUT 与 WAITING_CHILD）';
comment on column harness_tool_invocation.attempt is '已确认的 start 尝试次数（从 0 递增）';
comment on column harness_tool_invocation.approval is '审批记录（JSON object，可空）';
comment on column harness_tool_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column harness_tool_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column harness_tool_invocation.created_at is '创建时间（毫秒精度）';
comment on column harness_tool_invocation.updated_at is '最后更新时间（毫秒精度）';
comment on column harness_tool_invocation.input_receipt is '交互回执（JSON object，可空）：submissionId/actor/acceptedAt 的存在与终态关联由应用维护';

create index idx_harness_tool_invocation_thread_nonterminal
    on harness_tool_invocation (thread_id)
    where status in ('WAITING_APPROVAL', 'WAITING_INPUT', 'WAITING_CHILD', 'READY', 'RUNNING');

comment on index idx_harness_tool_invocation_thread_nonterminal is '未收尾 ToolInvocation 按公共 Thread 归属的查找路径（已终态行在 batch apply 后物理删除，不需要覆盖）';

create index idx_harness_tool_invocation_pending
    on harness_tool_invocation (created_at, id)
    where status in ('WAITING_APPROVAL', 'WAITING_INPUT');

comment on index idx_harness_tool_invocation_pending is '待处理（审批与人工输入）统一按 (created_at, id) 稳定分页，不按状态拆成两次查询';

create table durable_work (
    target_type varchar(16) not null,
    target_id uuid not null,
    available_at timestamptz(3) not null,
    wake_version bigint not null check (wake_version > 0),
    lease_token uuid,
    lease_until timestamptz(3),
    required_environment_id uuid,
    primary key (target_type, target_id),
    constraint fk_durable_work_environment foreign key (required_environment_id)
        references environment (id) on delete restrict,
    constraint ck_durable_work_target_type check (
        target_type ~ '^[A-Z][A-Z0-9_]{0,15}$'
    ),
    constraint ck_durable_work_lease_pair check (
        (lease_token is null) = (lease_until is null)
    )
);

comment on table durable_work is 'Durable Work：一个调度 target 的 mailbox 状态；wake_version 递增防止旧 worker 完成时吞掉新的 wake';
comment on column durable_work.target_type is '模块定义的 target 类型，1–16 个大写字母、数字或下划线，首字符为字母';
comment on column durable_work.target_id is 'target 实体的 UUID（与 target_type 构成主键）';
comment on column durable_work.available_at is '最早可被 claim 的时间（毫秒精度）';
comment on column durable_work.wake_version is 'wake 计数（从 1 递增）';
comment on column durable_work.lease_token is '当前 lease token UUID（与 lease_until 同时存在或同时缺失）';
comment on column durable_work.lease_until is '当前 lease 到期时间（毫秒精度）';
comment on column durable_work.required_environment_id is '执行该 Work 所需 Environment 的全局唯一 UUID；非空时仅持有该 environment READY 连接租约的节点可 claim';

create index idx_durable_work_available
    on durable_work (available_at, target_type, target_id);

create index idx_durable_work_lease_until
    on durable_work (lease_until, target_type, target_id)
    where lease_until is not null;

comment on index idx_durable_work_available is 'claim 按 (available_at, target_type, target_id) 选取候选';
comment on index idx_durable_work_lease_until is '过期 lease 扫描索引';

create table harness_thread_join (
    invocation_id uuid primary key,
    request_hash char(64) not null,
    parent_thread_id uuid,
    child_thread_id uuid not null,
    source_command_sequence bigint not null check (source_command_sequence > 0),
    agent varchar(256) not null,
    delivery varchar(32) not null,
    superseded_by_invocation_id uuid,
    max_turns integer,
    reminder_turn bigint not null default 0,
    terminal_entry_id uuid,
    final_answer_entry_id uuid,
    delivery_command_sequence bigint,
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_join_child_thread foreign key (child_thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_join_parent_thread foreign key (parent_thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_join_source_command foreign key (child_thread_id, source_command_sequence)
        references harness_thread_command (thread_id, sequence),
    constraint fk_harness_thread_join_terminal_entry foreign key (terminal_entry_id)
        references harness_entry (id),
    constraint fk_harness_thread_join_final_answer_entry foreign key (final_answer_entry_id)
        references harness_entry (id),
    constraint fk_harness_thread_join_delivery_command foreign key (parent_thread_id, delivery_command_sequence)
        references harness_thread_command (thread_id, sequence),
    constraint fk_harness_thread_join_superseded_by foreign key (superseded_by_invocation_id)
        references harness_thread_join (invocation_id),
    constraint ck_harness_thread_join_delivery_kind check (
        delivery in ('NOTIFY_PARENT', 'SETTLE_OWNER_TURN', 'SETTLE_TOOL_RESULT', 'RECEIPT_ONLY')
    ),
    constraint ck_harness_thread_join_request_hash check (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_join_parent_not_child check (
        parent_thread_id is null or parent_thread_id <> child_thread_id
    ),
    constraint ck_harness_thread_join_agent check (
        btrim(agent) <> ''
    ),
    constraint ck_harness_thread_join_max_turns check (
        max_turns is null or max_turns > 0
    ),
    constraint ck_harness_thread_join_reminder_turn check (
        reminder_turn >= 0
    )
);

comment on table harness_thread_join is 'Thread Join 契约记录：以 invocation_id 为主键记录原子源 prompt 接受与 join 契约、子 Thread 终止边界、匹配的 terminal/final_answer Entry、以及交付给父 Thread 的命令序列；无独立状态枚举，无 prompt/report 冗余复制';
comment on column harness_thread_join.invocation_id is 'Join 的全局唯一 UUID（主键，与发起调用的 Tool/Ticket invocation 对齐）';
comment on column harness_thread_join.request_hash is '创建请求指纹（64 位小写 SHA-256）';
comment on column harness_thread_join.parent_thread_id is '父 Thread UUID（可空，空表示 root one-shot completion ticket，不投递父消息）';
comment on column harness_thread_join.child_thread_id is '目标子 Thread UUID';
comment on column harness_thread_join.source_command_sequence is '子 Thread 接受源 prompt 的 command sequence';
comment on column harness_thread_join.agent is '本次执行的 Agent 名';
comment on column harness_thread_join.delivery is '结算后的交付方式：NOTIFY_PARENT 投递父通知；SETTLE_OWNER_TURN 冻结结果并唤醒父 Thread 的开放 Turn owner；SETTLE_TOOL_RESULT 冻结结果并结算父 Thread 中等待它的同步 task 调用；RECEIPT_ONLY 只冻结结果（无父 Thread）';
comment on column harness_thread_join.superseded_by_invocation_id is '接管本未完成 join 的后续 invocation UUID；被取代的 join 保留源命令与身份，不再占配额或交付';
comment on column harness_thread_join.max_turns is '软预算最大 turn 数（可空）';
comment on column harness_thread_join.reminder_turn is '已发出的 max_turns 软提醒轮次计数';
comment on column harness_thread_join.terminal_entry_id is '冻结的 terminal Entry UUID（可空，非空表示 matched）';
comment on column harness_thread_join.final_answer_entry_id is '可空的最终回答 Entry UUID（仅 terminal_entry_id 非空时可非空）';
comment on column harness_thread_join.delivery_command_sequence is '向父 Thread 投递结果 NOTIFICATION 的 command sequence（非空必须已 matched 且 parent_thread_id 非空）';
comment on column harness_thread_join.created_at is '创建时间（毫秒精度）';
comment on column harness_thread_join.updated_at is '最后更新时间（毫秒精度）';

create index idx_harness_thread_join_child_pending
    on harness_thread_join (child_thread_id)
    where terminal_entry_id is null and superseded_by_invocation_id is null;

comment on index idx_harness_thread_join_child_pending is '子 Thread 变为空闲或执行结束时检索等待匹配的 pending join';

create table harness_thread_stop_receipt (
    thread_id uuid not null,
    stop_request_id uuid not null,
    root_stop_request_id uuid not null,
    stopped_turn_end_entry_id uuid,
    created_at timestamptz(3) not null,
    constraint pk_harness_thread_stop_receipt primary key (thread_id, stop_request_id),
    constraint fk_harness_thread_stop_receipt_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_stop_receipt_turn_end foreign key (stopped_turn_end_entry_id)
        references harness_entry (id)
);

comment on table harness_thread_stop_receipt is 'Stop 回执：一次 Stop 在单个 Thread 上留下的持久身份与停止边界；集合由 root_stop_request_id 归组，被取消的输入从 command 表读取';
comment on column harness_thread_stop_receipt.thread_id is '所属 Thread';
comment on column harness_thread_stop_receipt.stop_request_id is '本次 Stop 在该 Thread 上的请求 ID；根 Thread 行与 root_stop_request_id 相等';
comment on column harness_thread_stop_receipt.root_stop_request_id is '本次 Stop 的根请求 ID；同一次 Stop 的全部回执共享它';
comment on column harness_thread_stop_receipt.stopped_turn_end_entry_id is '本次 Stop 关闭活跃 turn 时产生的 TURN_END Entry（若有）';
comment on column harness_thread_stop_receipt.created_at is '回执创建时间（毫秒精度）';

create index idx_harness_thread_stop_receipt_root_request
    on harness_thread_stop_receipt (root_stop_request_id, thread_id);

comment on index idx_harness_thread_stop_receipt_root_request is '按 root_stop_request_id 检索一次 Stop 产生的全部回执';

create table harness_execution_exit (
    execution_id uuid primary key,
    thread_id uuid not null,
    invocation_id uuid not null,
    attempt integer not null check (attempt >= 1),
    owner_lease_token uuid not null,
    kind varchar(16) not null,
    parent_execution_id uuid,
    environment_id uuid,
    state varchar(32) not null,
    registered_at timestamptz(3) not null,
    confirmed_at timestamptz(3),
    abandoned_at timestamptz(3),
    constraint fk_harness_execution_exit_thread foreign key (thread_id) references harness_thread (id),
    constraint fk_harness_execution_exit_parent foreign key (parent_execution_id)
        references harness_execution_exit (execution_id),
    constraint ck_harness_execution_exit_kind check (
        kind in ('DRIVER', 'TOOL', 'REMOTE')
    ),
    constraint ck_harness_execution_exit_state check (
        state in ('UNCONFIRMED', 'STUDIO_EXITED', 'OWNER_EXITED', 'REMOTE_ACKNOWLEDGED',
            'NOT_STARTED', 'ABANDONED')
    ),
    constraint ck_harness_execution_exit_owner_exited check (
        state <> 'OWNER_EXITED' or kind in ('DRIVER', 'TOOL')
    ),
    constraint ck_harness_execution_exit_time check (
        (confirmed_at is null or confirmed_at >= registered_at)
        and (abandoned_at is null or abandoned_at >= registered_at)
    )
);

comment on table harness_execution_exit is '具体执行代次的退出证据。独立于逻辑 Invocation 生命周期，不以 Invocation 外键跟随逻辑删除';
comment on column harness_execution_exit.execution_id is '这一具体执行代次的不可变身份';
comment on column harness_execution_exit.thread_id is '所属 Thread；按 Thread 查询，不依赖 open turn';
comment on column harness_execution_exit.invocation_id is '关联的逻辑 Driver 或 Tool invocation';
comment on column harness_execution_exit.attempt is '该 invocation 的具体执行代次，从 1 开始';
comment on column harness_execution_exit.owner_lease_token is '登记时持有的 Work lease token UUID';
comment on column harness_execution_exit.kind is 'DRIVER、TOOL 或挂在本地 TOOL/DRIVER 代次上的 REMOTE';
comment on column harness_execution_exit.parent_execution_id is 'REMOTE 关联的本地 TOOL 或 DRIVER 执行代次；本地执行为空';
comment on column harness_execution_exit.environment_id is 'REMOTE 执行关联的远端 Environment 标识；本地执行为空';
comment on column harness_execution_exit.state is '退出证据：未确认、Studio 退出、本地所有者退出、远端 ACK、确定未启动，或用户显式放弃（ABANDONED 不代表物理退出）';
comment on column harness_execution_exit.registered_at is '登记时间（毫秒精度）';
comment on column harness_execution_exit.confirmed_at is '真实退出证据确认时间（毫秒精度）；未确认或放弃时为空';
comment on column harness_execution_exit.abandoned_at is '用户显式放弃确认的时间（毫秒精度）；只有 ABANDONED 必填，后续真实确认保留';

create index idx_harness_execution_exit_thread_unconfirmed
    on harness_execution_exit (thread_id, registered_at, execution_id)
    where state = 'UNCONFIRMED';

comment on index idx_harness_execution_exit_thread_unconfirmed is '按 Thread 读取尚未确认退出的执行代次';

create index idx_harness_execution_exit_thread
    on harness_execution_exit (thread_id);

create index idx_harness_execution_exit_parent
    on harness_execution_exit (parent_execution_id)
    where parent_execution_id is not null;

create index idx_harness_execution_exit_invocation
    on harness_execution_exit (invocation_id, attempt);

create index idx_harness_execution_exit_unconfirmed_remote
    on harness_execution_exit (environment_id)
    where kind = 'REMOTE' and state = 'UNCONFIRMED';

comment on index idx_harness_execution_exit_thread is 'Thread 深删按 thread_id 删除执行代次，并服务删除 Thread 时的外键检查';
comment on index idx_harness_execution_exit_parent is '删除执行代次时服务 REMOTE 指向本地 TOOL 代次的自引用外键检查';
comment on index idx_harness_execution_exit_unconfirmed_remote is '按 Environment 读取尚未确认退出的远端执行代次';

create table session_blob_ref (
    session_id  uuid          not null,
    blob_id     uuid          not null,
    created_at  timestamptz(3) not null default current_timestamp,
    constraint pk_session_blob_ref primary key (session_id, blob_id),
    constraint fk_session_blob_ref_session foreign key (session_id)
        references harness_session (id) on delete restrict,
    constraint fk_session_blob_ref_blob foreign key (blob_id)
        references storage_blob (id) on delete restrict
);

create index idx_session_blob_ref_blob
    on session_blob_ref (blob_id, session_id);

comment on table session_blob_ref is 'Session 与 storage_blob 的显式引用边：每行恰好对应一次 blob retain，删除时由应用层逐行 release；FK 均为 RESTRICT，深删除必须先删本表';
comment on column session_blob_ref.session_id is '所属 Harness Session（RESTRICT FK）';
comment on column session_blob_ref.blob_id is '被引用的全局 blob（RESTRICT FK，ACTIVE 行）';
comment on column session_blob_ref.created_at is '引用创建时间（timestamptz，毫秒精度）';

comment on index idx_session_blob_ref_blob is '按 blob 反向枚举持有它的 Session（深删除与对账）';

-- 3. Platform Driver storage: catalogs, plugins, MCP and Builtin model invocations

create table provider (
    name            varchar(64)   primary key,
    description     text,
    provider_type   varchar(64)   not null,
    base_url        varchar(512),
    credential      varchar(512),
    config          jsonb         not null,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    version         bigint        not null default 0,
    constraint ck_provider_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
        and position('/' in name) = 0
    ),
    constraint ck_provider_provider_type check (
        provider_type in ('openai', 'openai_responses', 'anthropic', 'google')
    ),
    constraint ck_provider_version_nonneg check (version >= 0)
);

create table model (
    name            varchar(128)  primary key,
    kind            varchar(16)   not null,
    provider_name   varchar(64),
    model_id        varchar(256),
    description     text,
    config          jsonb,
    group_config    jsonb,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    version         bigint        not null default 0,
    constraint ck_model_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
    ),
    constraint ck_model_kind check (kind in ('MODEL', 'GROUP')),
    constraint ck_model_model_id check (
        model_id is null or (
            model_id !~ '^[[:space:]]'
            and model_id !~ '[[:space:]]$'
            and char_length(model_id) > 0
        )
    ),
    constraint fk_model_provider foreign key (provider_name)
        references provider (name) on update cascade,
    constraint ck_model_shape check (
        (kind = 'MODEL'
            and provider_name is not null
            and model_id is not null
            and config is not null
            and group_config is null)
        or (kind = 'GROUP'
            and provider_name is null
            and model_id is null
            and config is null
            and group_config is not null)
    ),
    constraint ck_model_group_config_object check (
        group_config is null or jsonb_typeof(group_config) = 'object'
    ),
    constraint ck_model_version_nonneg check (version >= 0)
);

comment on column model.name is '全局唯一逻辑模型名；单 Model 与 Model Group 共用同一名称空间';
comment on column model.kind is 'MODEL 或 GROUP；决定 provider/model/config 与 group_config 的互斥形状';
comment on column model.model_id is 'MODEL 发往上游 Provider 的真实 wire 模型标识：非空白、无环绕空白、≤256；与逻辑 name 独立且不唯一；GROUP 为 null';
comment on column model.group_config is 'GROUP 的有序成员、立即降级 HTTP 状态码与固定冷却秒数；MODEL 为 null';

create table agent (
    name            varchar(64)   primary key,
    description     text,
    system_prompt   text,
    execution       jsonb,
    config          jsonb         not null,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    version         bigint        not null default 0,
    constraint ck_agent_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
        and position('/' in name) = 0
    ),
    constraint ck_agent_execution_shape check (
        execution is null or case when jsonb_typeof(execution) = 'object' then coalesce(
            execution ? 'driver' and execution ? 'settings'
            and execution - 'driver' - 'settings' = '{}'::jsonb
            and jsonb_typeof(execution -> 'driver') = 'string'
            and length(btrim(execution ->> 'driver')) > 0
            and jsonb_typeof(execution -> 'settings') = 'object', false) else false end
    ),
    constraint ck_agent_version_nonneg check (version >= 0)
);

create index idx_agent_model_reference
    on agent ((execution -> 'settings' ->> 'modelName'))
    where execution ->> 'driver' = 'builtin';

create table model_group_cooldown (
    group_name          varchar(128)  not null,
    member_model_name   varchar(128)  not null,
    member_variant      varchar(64)   not null,
    group_version       bigint        not null,
    cooldown_until      timestamptz(3) not null,
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    constraint pk_model_group_cooldown
        primary key (group_name, member_model_name, member_variant),
    constraint fk_model_group_cooldown_group foreign key (group_name)
        references model (name) on update cascade on delete cascade,
    constraint fk_model_group_cooldown_member foreign key (member_model_name)
        references model (name) on update cascade on delete cascade,
    constraint ck_model_group_cooldown_variant check (
        member_variant = btrim(member_variant) and char_length(member_variant) > 0
    ),
    constraint ck_model_group_cooldown_version_nonneg check (group_version >= 0)
);

comment on table model_group_cooldown is 'Model Group 组内成员固定冷却截止时间；PostgreSQL 共享、按组配置版本隔离，到期自然失效';
comment on column model_group_cooldown.group_version is '写入时冻结的组配置版本；组配置变更后旧版本冷却不再参与选择';

create table skill_package (
    package_name     varchar(64)    not null,
    description      text,
    package_version  varchar(128)   not null,
    artifact_blob_id uuid           not null,
    manifest         jsonb          not null,
    published_at     timestamptz(3) not null,
    version          bigint         not null default 0,
    created_at       timestamptz(3) not null default current_timestamp,
    updated_at       timestamptz(3) not null default current_timestamp,
    constraint pk_skill_package primary key (package_name),
    constraint ck_skill_package_name check (
        package_name collate "C" ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    constraint ck_skill_package_description check (
        description is null
        or (
            description = btrim(description)
            and char_length(description) > 0
            and char_length(description) <= 1024
        )
    ),
    constraint ck_skill_package_version_text check (
        package_version = btrim(package_version)
        and char_length(package_version) between 1 and 128
        and package_version !~ '[[:cntrl:]]'
    ),
    constraint ck_skill_package_manifest check (jsonb_typeof(manifest) = 'object'),
    constraint ck_skill_package_published_at check (published_at is not null),
    constraint ck_skill_package_version check (version >= 0),
    constraint ck_skill_package_time_order check (updated_at >= created_at)
);

comment on table skill_package is 'Platform 全局 Skill Package 当前版本：每个 packageName 恰一行，保存当前版本号、规范化 ZIP 归档引用、从归档读出的 Skill 清单与发布时刻；不保留历史版本';
comment on column skill_package.package_name is 'package 名（主键；1–64 个小写字母或数字，以单个连字符分隔）';
comment on column skill_package.description is '可空 package 描述；去边缘空白后 1–1024 字符，null 表示未填写';
comment on column skill_package.package_version is '当前版本号：Git 渠道为 commit SHA，上传渠道为用户填写的文本（1–128 字符，无控制字符）';
comment on column skill_package.artifact_blob_id is '当前版本规范化 ZIP 归档的 storage_blob 引用；发布切换时原子替换并在同一事务释放旧引用';
comment on column skill_package.manifest is '从最终 ZIP 读出的 Skill 清单（JSON object {skills:[{name,description}]}，按 name 排序）；不信任客户端提交的结构';
comment on column skill_package.published_at is '当前版本发布时间（毫秒精度）';
comment on column skill_package.version is 'CAS 乐观锁版本：非负，从 0 开始，实际事实变化时 +1';
comment on column skill_package.created_at is '创建时间（毫秒精度）';
comment on column skill_package.updated_at is '最后更新时间（毫秒精度），应用侧维护';

create table skill_package_git_source (
    package_name   varchar(64)   not null,
    repository_url text          not null,
    branch         varchar(255)  not null,
    token          text,
    head_commit    varchar(64)   not null,
    checked_at     timestamptz(3) not null,
    check_error    text,
    constraint pk_skill_package_git_source primary key (package_name),
    constraint fk_skill_package_git_source_package foreign key (package_name)
        references skill_package (package_name) on delete cascade,
    constraint ck_skill_package_git_source_repository_url check (
        repository_url = btrim(repository_url)
        and repository_url ~ '^[a-z][a-z0-9+.-]*://[^[:space:]]+$'
        and char_length(repository_url) <= 2048
        and repository_url !~ '[[:cntrl:]]'
        and repository_url !~ '://[^/[:space:]]*@'
    ),
    constraint ck_skill_package_git_source_branch check (
        branch = btrim(branch)
        and char_length(branch) > 0
        and branch !~ '[[:cntrl:]]'
    ),
    constraint ck_skill_package_git_source_token check (
        token is null
        or length(token) > 0
    ),
    constraint ck_skill_package_git_source_head_commit check (
        head_commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'
    ),
    constraint ck_skill_package_git_source_check_error check (
        check_error is null
        or (
            check_error = btrim(check_error)
            and char_length(check_error) > 0
            and octet_length(check_error) <= 4096
        )
    )
);

create index idx_skill_package_git_source_head_commit
    on skill_package_git_source (head_commit);

comment on table skill_package_git_source is 'Git 渠道 Skill Package 的仓库事实：只有 Git 渠道的包才有这一行；上传渠道的包没有';
comment on column skill_package_git_source.package_name is '所属 package（主键，随 skill_package 删除级联）';
comment on column skill_package_git_source.repository_url is '不可变 Git repository URL（必须带 scheme、无内嵌 userinfo、无空白、≤2048）；更换仓库必须新建 Package';
comment on column skill_package_git_source.branch is '只用于检查候选更新的 branch（非空白、无环绕空白、无控制字符、≤255）；不决定发布内容';
comment on column skill_package_git_source.token is '私有仓库访问令牌；null 表示匿名访问，任何常规 DTO/预览/日志/模型上下文都不得返回，也不下发给 Daemon';
comment on column skill_package_git_source.head_commit is '最近一次检查到的 branch HEAD（40 或 64 位小写 hex）；发布该 commit 后与 package_version 一致';
comment on column skill_package_git_source.checked_at is '最近一次检查时间（毫秒精度）';
comment on column skill_package_git_source.check_error is '最近一次检查的有界错误摘要（非空、无环绕空白、≤4096 字节）；检查成功时为 null';

create table plugin_configuration (
    plugin_id   varchar(64)    primary key,
    version     bigint         not null,
    values_json text           not null,
    created_at  timestamptz(3) not null default current_timestamp,
    updated_at  timestamptz(3) not null default current_timestamp,
    constraint ck_plugin_configuration_plugin_id check (
        plugin_id ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
    ),
    constraint ck_plugin_configuration_version check (version > 0),
    constraint ck_plugin_configuration_values_object check (
        values_json is json object with unique keys
    ),
    constraint ck_plugin_configuration_values_budget check (
        octet_length(values_json) <= 65536
    ),
    constraint ck_plugin_configuration_time_order check (updated_at >= created_at)
);

comment on table plugin_configuration is '已安装 Plugin 的当前普通配置：每个 Plugin 一行，秘密不进入本表';
comment on column plugin_configuration.plugin_id is 'Plugin 安装身份，与 classpath 中的 Plugin.pluginId 对齐';
comment on column plugin_configuration.version is '配置修订：首次写入为 1，每次保存 +1，写入以它做 CAS';
comment on column plugin_configuration.values_json is '按插件 Schema 归一化后的严格 JSON object 文本（≤64 KiB）';

create table plugin_credential (
    plugin_id           varchar(64)    primary key,
    generation          uuid           not null default gen_random_uuid(),
    payload_json        text           not null,
    region              varchar(64),
    expires_at          timestamptz(3),
    next_refresh_at     timestamptz(3),
    status              varchar(32)    not null,
    last_refreshed_at   timestamptz(3),
    last_refresh_error  text,
    refresh_lease_token uuid,
    refresh_lease_until timestamptz(3),
    version             bigint         not null default 0,
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    constraint ck_plugin_credential_plugin_id check (
        plugin_id ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
    ),
    constraint ck_plugin_credential_payload_object check (
        payload_json is json object
    ),
    constraint ck_plugin_credential_payload_budget check (
        octet_length(payload_json) <= 65536
    ),
    constraint ck_plugin_credential_route_metadata check (
        (region is null) = (expires_at is null)
        and (region is null) = (next_refresh_at is null)
    ),
    constraint ck_plugin_credential_status check (
        status in ('CONNECTED', 'REFRESH_FAILED', 'REFRESH_UNCERTAIN', 'REAUTH_REQUIRED')
    ),
    constraint ck_plugin_credential_last_refresh_error check (
        last_refresh_error is null or octet_length(last_refresh_error) <= 4096
    ),
    constraint ck_plugin_credential_refresh_lease check (
        (refresh_lease_token is null) = (refresh_lease_until is null)
    ),
    constraint ck_plugin_credential_version check (version >= 0),
    constraint ck_plugin_credential_time_order check (updated_at >= created_at)
);

comment on table plugin_credential is '已认证 Plugin 的凭据：每个 Plugin 至多一行，断连即删除；交互凭据与静态密钥共用本表';
comment on column plugin_credential.payload_json is '凭据载荷 JSON object（静态密钥为声明的秘密字段，交互凭据为插件不透明载荷）；任何查询投影都不得返回';
comment on column plugin_credential.region is '交互凭据的 region 路由元数据；静态密钥为 null';
comment on column plugin_credential.expires_at is '交互凭据的本地失效时刻；静态密钥为 null';
comment on column plugin_credential.next_refresh_at is '下一次刷新时刻；静态密钥为 null，因此永不被领取';
comment on column plugin_credential.status is '状态：CONNECTED, REFRESH_FAILED, REFRESH_UNCERTAIN, REAUTH_REQUIRED';
comment on column plugin_credential.refresh_lease_token is '跨节点刷新租约令牌 UUID，与 refresh_lease_until 成对';
comment on column plugin_credential.version is 'CAS 版本：每次写入 +1；调用期拒绝与刷新终结都匹配它';

create index idx_plugin_credential_refresh_due
    on plugin_credential (next_refresh_at);

create table plugin_auth_attempt (
    plugin_id       varchar(64)    primary key,
    nonce_hash      char(64)       not null,
    config_version  bigint         not null,
    region          varchar(64)    not null,
    expires_at      timestamptz(3) not null,
    created_at      timestamptz(3) not null default current_timestamp,
    constraint ck_plugin_auth_attempt_nonce_hash check (nonce_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_plugin_auth_attempt_config_version check (config_version > 0),
    constraint ck_plugin_auth_attempt_region check (char_length(region) between 1 and 64)
);

create table mcp_server (
    name              varchar(32)    primary key,
    url               varchar(2048)  not null,
    headers           jsonb          not null default '{}'::jsonb,
    enabled           boolean        not null default true,
    timeout_millis    bigint         not null,
    discovery_status  varchar(16)    not null default 'UNVERIFIED',
    created_at        timestamptz(3) not null default current_timestamp,
    updated_at        timestamptz(3) not null default current_timestamp,
    version           bigint         not null default 0,
    constraint ck_mcp_server_name check (
        name ~ '^[a-z][a-z0-9_]*$'
    ),
    constraint ck_mcp_server_url_nonblank check (btrim(url) <> ''),
    constraint ck_mcp_server_headers_object check (
        jsonb_typeof(headers) = 'object'
    ),
    constraint ck_mcp_server_timeout_positive check (timeout_millis > 0),
    constraint ck_mcp_server_discovery_status check (
        discovery_status in ('UNVERIFIED', 'AVAILABLE')
    ),
    constraint ck_mcp_server_version_nonneg check (version >= 0),
    constraint ck_mcp_server_time_order check (updated_at >= created_at)
);

comment on table mcp_server is 'Platform MCP Server 持久配置：name 即主键与不可变路由身份，仅支持 Streamable HTTP 传输';
comment on column mcp_server.name is '唯一名与主键（创建后不可变）：^[a-z][a-z0-9_]*$，≤32 字符；同时作为模型工具名 mcp_<name>_<tool> 的组成段';
comment on column mcp_server.url is 'Streamable HTTP endpoint URL（http/https 绝对地址，不得内嵌 user-info 凭据）';
comment on column mcp_server.headers is '自定义请求 header JSON object；值支持环境变量整值占位符，由 Backend 从进程环境替换，绝不回显';
comment on column mcp_server.enabled is '公共启用开关：默认 true；false 时即使 AVAILABLE 也不可被 Agent 选择';
comment on column mcp_server.timeout_millis is '正整数毫秒超时：连接、发现与 tools/call 共用';
comment on column mcp_server.discovery_status is '发现状态：UNVERIFIED（未验证）、AVAILABLE（可用）；仅 enabled 且 AVAILABLE 进入运行时目录';
comment on column mcp_server.created_at is '创建时间（毫秒精度）';
comment on column mcp_server.updated_at is '最后更新时间（毫秒精度），应用侧维护';
comment on column mcp_server.version is '乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据';

create table mcp_tool (
    name           varchar(64)   primary key,
    server_name    varchar(32)   not null,
    source_name    varchar(128)  not null,
    description    text          not null,
    input_schema   jsonb         not null,
    constraint fk_mcp_tool_server foreign key (server_name)
        references mcp_server (name) on delete cascade,
    constraint ck_mcp_tool_name check (
        name ~ '[A-Za-z][A-Za-z0-9_-]*'
    ),
    constraint ck_mcp_tool_source_name check (char_length(source_name) > 0),
    constraint ck_mcp_tool_description_nonblank check (btrim(description) <> ''),
    constraint ck_mcp_tool_input_schema_object check (
        jsonb_typeof(input_schema) = 'object'
    )
);

create unique index uk_mcp_tool_server_source_name
    on mcp_tool (server_name, source_name);

comment on table mcp_tool is 'MCP Server 当前发现结果的工具行：name 即模型可见工具身份与主键，成功发现整体替换，父 Server 删除时级联清理';
comment on column mcp_tool.name is '模型可见工具名（主键）：mcp_<server_name>_<normalized_source_tool_name>，须满足 ToolDescriptor name 语法且 ≤64';
comment on column mcp_tool.server_name is '所属 MCP Server name；随父行删除级联硬删除';
comment on column mcp_tool.source_name is 'MCP 工具原始名（同一 Server 内唯一）';
comment on column mcp_tool.description is '工具描述（非空白），冻结进 ToolDescriptor';
comment on column mcp_tool.input_schema is '工具 JSON input schema（JSON object），冻结进 ToolDescriptor';

create table builtin_model_invocation (
    id uuid primary key,
    thread_id uuid not null,
    turn_start_entry_id uuid not null,
    request_head_entry_id uuid not null,
    request_spec jsonb not null check (jsonb_typeof(request_spec) = 'object'),
    status varchar(16) not null,
    attempt integer not null check (attempt >= 0),
    stream_checkpoint jsonb check (
        stream_checkpoint is null or jsonb_typeof(stream_checkpoint) = 'object'
    ),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id uuid,
    failed_attempts jsonb not null check (jsonb_typeof(failed_attempts) = 'array'),
    group_execution jsonb check (
        group_execution is null or jsonb_typeof(group_execution) = 'object'
    ),
    requested_at timestamptz(3),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_builtin_model_invocation_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_builtin_model_invocation_turn_start foreign key (turn_start_entry_id)
        references harness_entry (id),
    constraint fk_builtin_model_invocation_request_head foreign key (request_head_entry_id)
        references harness_entry (id),
    constraint fk_builtin_model_invocation_result foreign key (result_entry_id)
        references harness_entry (id),
    constraint uk_builtin_model_invocation_turn unique (thread_id, turn_start_entry_id),
    constraint ck_builtin_model_invocation_status check (
        status in (
            'READY',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'UNKNOWN'
        )
    ),
    constraint ck_builtin_model_invocation_terminal_facts check (
        result is null or error is null
    )
);

comment on table builtin_model_invocation is 'ModelInvocation：一次 model turn 的 durable 生命周期记录（READY/RUNNING 与四个 terminal）';
comment on column builtin_model_invocation.id is 'ModelInvocation 的全局唯一 UUID';
comment on column builtin_model_invocation.thread_id is '所属 Thread';
comment on column builtin_model_invocation.turn_start_entry_id is '本次 turn 的 TURN_START Entry';
comment on column builtin_model_invocation.request_head_entry_id is '创建时的 Thread head（request 头 Entry CAS 快照）';
comment on column builtin_model_invocation.request_spec is '冻结的 model 请求规格（JSON object）';
comment on column builtin_model_invocation.status is '生命周期状态';
comment on column builtin_model_invocation.attempt is '已持久化 RUNNING 的执行次数（从 0 递增，先于请求发出）';
comment on column builtin_model_invocation.stream_checkpoint is 'RUNNING 流式断点（JSON object，可空）';
comment on column builtin_model_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column builtin_model_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column builtin_model_invocation.result_entry_id is '结果 Entry（Assistant/AssistantError/AssistantAborted/COMPACTION），全局唯一';
comment on column builtin_model_invocation.failed_attempts is '由 retry policy 驱动的 append-only TRANSIENT 失败 attempt 历史（JSON array）';
comment on column builtin_model_invocation.group_execution is 'Model Group 当前成员游标与该成员普通重试计数（JSON object，单 Model 为 null）';
comment on column builtin_model_invocation.requested_at is '当前已确认 Provider 调用的发起时间；成功历史 metadata 记录实际请求时刻';
comment on column builtin_model_invocation.created_at is '创建时间（毫秒精度）';
comment on column builtin_model_invocation.updated_at is '最后更新时间（毫秒精度）';

create unique index uk_builtin_model_invocation_result
    on builtin_model_invocation (result_entry_id)
    where result_entry_id is not null;

comment on index uk_builtin_model_invocation_result is 'resultEntryId 全局唯一（非 null 时）';

create index idx_builtin_model_invocation_thread_nonterminal
    on builtin_model_invocation (thread_id)
    where status in ('READY', 'RUNNING');

alter table skill_package
    add constraint fk_skill_package_artifact
        foreign key (artifact_blob_id) references storage_blob (id) on delete restrict;

-- 4. Product tables: Canvas, Chat and Project

create table canvas_document (
    id uuid primary key,
    title varchar(256) not null check (btrim(title) <> ''),
    revision bigint not null default 0 check (revision >= 0),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp
);

comment on table canvas_document is 'Canvas 聚合头：revision 是单调递增的同步位置，旧 ACK 不覆盖新输入；标题规范化由应用负责，DB 只拒绝空白标题';
comment on column canvas_document.id is 'Canvas 全局唯一 UUID（服务端生成）';
comment on column canvas_document.revision is 'graph 同步位置：任何成功命令批或 Function Run 状态前进恰好 +1';
comment on column canvas_document.created_at is '创建时间（毫秒精度）';
comment on column canvas_document.updated_at is '最后更新时间（毫秒精度），应用侧维护';

create index idx_canvas_document_updated
    on canvas_document (updated_at, id);

create table canvas_group (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    title varchar(256) not null check (btrim(title) <> ''),
    x double precision not null,
    y double precision not null,
    width double precision not null,
    height double precision not null,
    unique (canvas_id, id),
    constraint ck_canvas_group_geometry check (
        x not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and y not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and width > 0 and width < 'Infinity'::float8
        and height > 0 and height < 'Infinity'::float8
    )
);

comment on table canvas_group is '不嵌套、使用 world 坐标的 Canvas group；(canvas_id, id) 同时是节点同 Canvas 复合引用的目标';
comment on column canvas_group.id is 'Group 全局唯一 UUID（客户端生成）';
comment on column canvas_group.canvas_id is '所属 Canvas（RESTRICT）';
comment on column canvas_group.title is '规范化标题（非空白，<= 256 字符）';
comment on column canvas_group.x is 'world 坐标 x（有限数）';
comment on column canvas_group.y is 'world 坐标 y（有限数）';
comment on column canvas_group.width is '正宽度（有限数）';
comment on column canvas_group.height is '正高度（有限数）';

create table canvas_node (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    name varchar(256) not null check (btrim(name) <> '' and name = btrim(name)),
    -- Compatibility-folded uniqueness key. btrim runs after normalize so that
    -- NBSP and other compatibility whitespace cannot smuggle a duplicate or an
    -- all-whitespace name; control characters stay an application concern.
    name_key text generated always as (lower(btrim(normalize(name, NFKC)))) stored,
    x double precision not null,
    y double precision not null,
    width double precision not null,
    height double precision not null,
    group_id uuid,
    "function" jsonb,
    constraint uk_canvas_node_canvas_id unique (canvas_id, id),
    constraint uk_canvas_node_canvas_name_key unique (canvas_id, name_key),
    constraint fk_canvas_node_group foreign key (canvas_id, group_id)
        references canvas_group (canvas_id, id) on delete restrict,
    constraint ck_canvas_node_name_key check (name_key <> ''),
    constraint ck_canvas_node_geometry check (
        x not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and y not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and width > 0 and width < 'Infinity'::float8
        and height > 0 and height < 'Infinity'::float8
    ),
    -- Base shape {name,args}; strict plugin schemas and resource-reference
    -- validation are application responsibilities.
    constraint ck_canvas_node_function check (
        "function" is null
        or (
            jsonb_typeof("function") = 'object'
            and coalesce(jsonb_typeof("function" -> 'name'), '') = 'string'
            and coalesce(btrim("function" ->> 'name'), '') <> ''
            and coalesce(jsonb_typeof("function" -> 'args'), '') = 'object'
        )
    )
);

comment on table canvas_node is 'Canvas 唯一的节点形态：普通资源节点或可携带资源输出的 Function 节点；name_key 规范化折叠空白与大小写后同 Canvas 唯一';
comment on column canvas_node.id is 'Node 全局唯一 UUID（客户端生成）';
comment on column canvas_node.canvas_id is '所属 Canvas（RESTRICT）';
comment on column canvas_node.name is '展示名（非空白且无环绕空白；控制字符由应用校验）';
comment on column canvas_node.name_key is '唯一性键：lower(btrim(normalize(name, NFKC)))';
comment on column canvas_node.x is 'world 坐标 x（有限数）';
comment on column canvas_node.y is 'world 坐标 y（有限数）';
comment on column canvas_node.width is '正宽度（有限数）';
comment on column canvas_node.height is '正高度（有限数）';
comment on column canvas_node.group_id is '所属 Group（可空，必须与节点同 Canvas）';
comment on column canvas_node."function" is 'Function 配置 {name,args}（可空；严格插件 schema 与资源引用由应用校验）';

create index idx_canvas_node_group on canvas_node (canvas_id, group_id) where group_id is not null;

create table canvas_resource (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    owner_node_id uuid,
    resource_index integer,
    name varchar(512) not null check (btrim(name) <> ''),
    blob_id uuid,
    text_content text,
    created_at timestamptz(3) not null default current_timestamp,
    constraint uk_canvas_resource_canvas_id unique (canvas_id, id),
    constraint uk_canvas_resource_slot unique (canvas_id, owner_node_id, resource_index),
    constraint fk_canvas_resource_owner foreign key (canvas_id, owner_node_id)
        references canvas_node (canvas_id, id) on delete restrict,
    constraint ck_canvas_resource_owner_pair check ((owner_node_id is null) = (resource_index is null)),
    constraint ck_canvas_resource_index check (resource_index is null or resource_index >= 0),
    constraint ck_canvas_resource_content check ((blob_id is null) <> (text_content is null))
);

comment on table canvas_resource is '不可变资源行：可见资源直接属于节点（owner_node_id + resource_index）；Function 目标物化与 pinned orphan 可暂时无 owner；内容要么是全局 Storage blob 引用要么是内联文本';
comment on column canvas_resource.id is 'Resource 全局唯一 UUID（服务端生成）';
comment on column canvas_resource.canvas_id is '所属 Canvas（RESTRICT）';
comment on column canvas_resource.owner_node_id is '所属节点；与 resource_index 同存同缺，无 owner 的资源只可由 Function pin 保活';
comment on column canvas_resource.resource_index is '节点内从 0 递增的资源序号；与 owner_node_id 同存同缺';
comment on column canvas_resource.name is '资源显示名（非空白，<= 512 字符）';
comment on column canvas_resource.blob_id is '全局 Storage blob 引用（TEXT 资源为 null；RESTRICT，引用计数由全局存储管理）';
comment on column canvas_resource.text_content is 'TEXT 资源的内联内容（blob 资源为 null），与 blob_id 恰好互斥';
comment on column canvas_resource.created_at is '创建时间（毫秒精度）';

create index idx_canvas_resource_blob on canvas_resource (blob_id) where blob_id is not null;
create index idx_canvas_resource_created on canvas_resource (canvas_id, created_at, id);

create table canvas_function_run (
    node_id uuid primary key references canvas_node (id) on delete restrict,
    request_id uuid not null,
    status varchar(16) not null,
    attempt integer not null default 0 check (attempt >= 0),
    state_json jsonb not null check (jsonb_typeof(state_json) = 'object'),
    error text,
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    check (status in ('READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'))
);

comment on table canvas_function_run is 'Function 节点当前/最后一次 Run：PK 为 node_id，request_id 是客户端幂等键；调度与执行租约在 durable_work（CANVAS_RUN）';
comment on column canvas_function_run.node_id is 'Function 节点（一个节点同时至多一个 Run 行）';
comment on column canvas_function_run.request_id is 'Run 请求 UUID（客户端生成，幂等键）';
comment on column canvas_function_run.status is '生命周期：READY / RUNNING / SUCCEEDED / FAILED / CANCELLED / UNKNOWN';
comment on column canvas_function_run.attempt is '成功 claim 次数；首次 claim 从 0 增加为 1';
comment on column canvas_function_run.state_json is 'typed/versioned 冻结计划与 checkpoint（JSON object）';
comment on column canvas_function_run.error is '公开错误信息；失败原因形状由应用维护';
comment on column canvas_function_run.created_at is '当前 request 创建时间（毫秒精度）';
comment on column canvas_function_run.updated_at is '最后更新时间（毫秒精度）';

create table canvas_function_resource_pin (
    canvas_id uuid not null,
    node_id uuid not null,
    request_id uuid not null,
    role varchar(16) not null check (role in ('INPUT', 'OUTPUT')),
    resource_id uuid not null,
    primary key (canvas_id, node_id, request_id, role, resource_id),
    constraint fk_canvas_pin_node foreign key (canvas_id, node_id)
        references canvas_node (canvas_id, id) on delete restrict,
    constraint fk_canvas_pin_run foreign key (node_id)
        references canvas_function_run (node_id) on delete restrict,
    constraint fk_canvas_pin_resource foreign key (canvas_id, resource_id)
        references canvas_resource (canvas_id, id) on delete restrict
);

comment on table canvas_function_resource_pin is 'Function Run 生命周期内对资源的 pin：INPUT 为启动时冻结的引用资源，OUTPUT 为预分配的目标资源；只保护生命周期，绝不参与 blob 引用计数';
comment on column canvas_function_resource_pin.canvas_id is '所属 Canvas';
comment on column canvas_function_resource_pin.node_id is 'Function 节点';
comment on column canvas_function_resource_pin.request_id is 'Run 请求 UUID';
comment on column canvas_function_resource_pin.role is 'pin 角色：INPUT / OUTPUT';
comment on column canvas_function_resource_pin.resource_id is '被 pin 的资源（必须已存在）';

create index idx_canvas_function_pin_resource on canvas_function_resource_pin (canvas_id, resource_id);

create table canvas_command_dedup (
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    idempotency_key uuid not null,
    request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    accepted_revision bigint not null,
    constraint pk_canvas_command_dedup primary key (canvas_id, idempotency_key),
    constraint ck_canvas_command_dedup_revision check (accepted_revision >= 0)
);

comment on table canvas_command_dedup is '命令批幂等：相同 (canvas_id, idempotency_key) 只能以相同 request_hash 精确回放一次；graph 同步位置由 canvas_document.revision 负责，本表不冗余存储';
comment on column canvas_command_dedup.canvas_id is '所属 Canvas（RESTRICT）';
comment on column canvas_command_dedup.idempotency_key is '客户端整批命令的幂等 UUID';
comment on column canvas_command_dedup.request_hash is '整批命令的 SHA-256（64 位小写十六进制）';
comment on column canvas_command_dedup.accepted_revision is '首次接受时的 revision 位置（非负）；重放不改变它';

alter table canvas_resource
    add constraint fk_canvas_resource_blob foreign key (blob_id)
    references storage_blob (id) on delete restrict;
create table chat (
    id                  uuid          primary key,
    title               varchar(256),
    -- agent_name 故意不加 FK：它只按名称引用 Agent。Agent 硬删除期间该引用失效
    -- （turn/attempt fail closed），同名重建后既有 Chat 引用解析到当前 Agent。
    agent_name          varchar(64)   not null,
    -- environment_name 故意不加 FK：它只按名称引用 Environment。Environment 硬删除期间该引用
    -- 失效（发送时 planner fail closed），同名重建后既有 Chat 引用解析到当前 Environment。
    environment_name    varchar(64),
    yolo_enabled        boolean       not null default false,
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    version             bigint        not null default 0,
    constraint ck_chat_version_nonneg check (version >= 0)
);

create index idx_chat_modified on chat (updated_at, created_at);

create table chat_session (
    session_id uuid,
    chat_id uuid not null,
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_chat_session primary key (session_id),
    constraint fk_chat_session_session foreign key (session_id)
        references harness_session (id) on delete restrict,
    constraint fk_chat_session_chat foreign key (chat_id)
        references chat (id) on delete restrict
);

comment on table chat_session is 'Chat 与 Harness Session 的直接关联：一个 Session 至多属于一个 Chat，删除任一侧前必须先删本行';
comment on column chat_session.session_id is '该 Chat 持有的 Harness Session（主键，RESTRICT FK）';
comment on column chat_session.chat_id is '所属 Chat（RESTRICT FK）';
comment on column chat_session.created_at is '关联建立时间（毫秒精度）';

create index idx_chat_session_chat on chat_session (chat_id, created_at, session_id);

create table chat_space (
    id                     uuid          primary key,
    owner_user_id          uuid          not null references studio_user (id) on delete restrict,
    title                  varchar(256)  not null,
    title_key              text          not null,
    archived_at            timestamptz(3),
    default_agent_name     varchar(64)   references agent (name) on delete restrict,
    default_environment_id uuid          references environment (id) on delete set null,
    default_yolo_enabled   boolean       not null default false,
    version                bigint        not null default 0,
    tree_version           bigint        not null default 0,
    create_request_hash    text          not null,
    constraint ck_chat_space_create_hash check (create_request_hash ~ '^[0-9a-f]{64}$'),
    constraint uk_chat_space_owner_title unique (owner_user_id, title_key),
    constraint ck_chat_space_versions check (version >= 0 and tree_version >= 0),
    constraint ck_chat_space_title check (title <> '' and title_key <> '')
);

comment on table chat_space is '用户拥有的工作区边界；归档不移除目录、资源或执行历史';
comment on column chat_space.id is '空间 ID，也是工作区树的标识';
comment on column chat_space.owner_user_id is '空间所有者；管理员不能访问他人的空间';
comment on column chat_space.title is '去除首尾空白后的空间名称';
comment on column chat_space.title_key is '同一用户下忽略大小写的名称键，含归档空间';
comment on column chat_space.archived_at is '归档时间；为空表示活跃';
comment on column chat_space.default_agent_name is '默认 Agent，可为空；引用时不可删除 Agent';
comment on column chat_space.default_environment_id is '默认环境，环境删除时清空';
comment on column chat_space.default_yolo_enabled is '新建会话默认权限';
comment on column chat_space.version is '空间配置版本，不因目录结构变化递增';
comment on column chat_space.tree_version is '目录结构版本，结构写在空间行锁内递增';
comment on column chat_space.create_request_hash is '原始创建参数的 SHA-256；配置变化不影响创建重放';

create table workspace_folder (
    id           uuid          primary key,
    workspace_id uuid          not null references chat_space (id) on delete restrict,
    parent_id    uuid,
    name         varchar(256)  not null,
    name_key     text          not null,
    position     integer       not null,
    create_request_hash text,
    created_tree_version bigint,
    constraint ck_workspace_folder_create check (
        (create_request_hash is null and created_tree_version is null) or
        (create_request_hash is not null and created_tree_version is not null
            and create_request_hash ~ '^[0-9a-f]{64}$' and created_tree_version > 0)
    ),
    constraint uk_workspace_folder_id unique (workspace_id, id),
    constraint fk_workspace_folder_parent foreign key (workspace_id, parent_id)
        references workspace_folder (workspace_id, id) on delete restrict,
    constraint uk_workspace_folder_name unique nulls not distinct (workspace_id, parent_id, name_key),
    constraint uk_workspace_folder_position unique nulls not distinct (workspace_id, parent_id, position)
        deferrable initially deferred,
    constraint ck_workspace_folder_position check (position >= 0),
    constraint ck_workspace_folder_parent check (parent_id <> id),
    constraint ck_workspace_folder_name check (name <> '' and name_key <> '')
);

comment on table workspace_folder is '空间目录；跨空间父关系由复合外键禁止，事务持空间锁校验无环';
comment on column workspace_folder.id is '目录 ID';
comment on column workspace_folder.workspace_id is '所属 Chat Space';
comment on column workspace_folder.parent_id is '同一空间的父目录；为空表示顶层';
comment on column workspace_folder.name is '去除首尾空白后的目录名称';
comment on column workspace_folder.name_key is '同级忽略大小写的目录名称键';
comment on column workspace_folder.position is '同级目录中从零开始的顺序';
comment on column workspace_folder.create_request_hash is '独立创建目录的原始请求摘要；空间自带的目录为空';
comment on column workspace_folder.created_tree_version is '独立创建目录首次提交返回的树版本；重放不改变它';

create table workspace_resource (
    id           uuid        primary key,
    workspace_id uuid        not null,
    folder_id    uuid        not null,
    position     integer     not null,
    session_id   uuid        unique references harness_session (id) on delete restrict,
    canvas_id    uuid        unique references canvas_document (id) on delete restrict,
    agent_name   varchar(64) references agent (name) on delete restrict,
    create_request_hash text,
    created_tree_version bigint,
    initial_command_id uuid,
    constraint ck_workspace_resource_create check (
        (create_request_hash is null and created_tree_version is null and initial_command_id is null) or
        (create_request_hash is not null and created_tree_version is not null
            and create_request_hash ~ '^[0-9a-f]{64}$' and created_tree_version > 0)
    ),
    constraint fk_workspace_resource_folder foreign key (workspace_id, folder_id)
        references workspace_folder (workspace_id, id) on delete restrict,
    constraint uk_workspace_resource_session unique (workspace_id, session_id),
    constraint uk_workspace_resource_position unique (workspace_id, folder_id, position)
        deferrable initially deferred,
    constraint ck_workspace_resource_position check (position >= 0),
    constraint ck_workspace_resource_kind check (num_nonnulls(session_id, canvas_id) = 1),
    constraint ck_workspace_resource_id check (id = coalesce(session_id, canvas_id)),
    constraint ck_workspace_resource_agent check ((session_id is null) = (agent_name is null))
);

comment on table workspace_resource is '目录与领域对象的唯一归属边，不保存领域对象的标题';
comment on column workspace_resource.id is '领域对象 ID，与 Session 或 Canvas ID 相同';
comment on column workspace_resource.workspace_id is '所属空间，由目录复合外键约束';
comment on column workspace_resource.folder_id is '同一空间内放置该资源的目录';
comment on column workspace_resource.position is '目录内资源序列中从零开始的顺序';
comment on column workspace_resource.session_id is 'Session 资源 ID，与 canvas_id 互斥';
comment on column workspace_resource.canvas_id is 'Canvas 资源 ID，与 session_id 互斥';
comment on column workspace_resource.agent_name is 'Session 的固定 Agent；Canvas 不设置';
comment on column workspace_resource.create_request_hash is '资源创建请求摘要，随归属边删除';
comment on column workspace_resource.created_tree_version is '资源首次创建提交后的树版本';
comment on column workspace_resource.initial_command_id is '首条消息接受结果的原始 Command ID；不是持有边';

create index idx_chat_space_default_agent on chat_space (default_agent_name) where default_agent_name is not null;
create index idx_chat_space_default_environment on chat_space (default_environment_id) where default_environment_id is not null;
create index idx_workspace_resource_agent on workspace_resource (agent_name) where agent_name is not null;
