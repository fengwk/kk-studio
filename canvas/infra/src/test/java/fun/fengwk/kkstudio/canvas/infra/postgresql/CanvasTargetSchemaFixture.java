package fun.fengwk.kkstudio.canvas.infra.postgresql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Canvas 目标 schema 的测试夹具。
 *
 * <p>权威 schema 模块的 Flyway baseline 仍描述旧的整图 {@code version}、link 与 session 模型，而本模块的适配器只面向 {@code
 * docs/canvas-project.sql} 的目标模型。DML 与 DDL 的权威来源是那条目标 DDL，因此这里以 Java 语句在 baseline 之上重建 canvas
 * 相关的全部表：先 {@code drop ... cascade} 移除旧表与引用它们的约束，再按目标定义创建，避免在 {@code schema/} 之外新增迁移文件。
 */
final class CanvasTargetSchemaFixture {

  /** 夹具只允许作用于专用隔离测试库；任何其他库（尤其部署库）直接拒绝，避免 DDL 与级联删除误伤真实数据。 */
  private static final String TEST_DATABASE = "kk_studio_canvas_infra";

  /** 依赖顺序无关：cascade 清理旧表及其引用约束（baseline 中 session_owner 等非 canvas 表仍引用 canvas_document）。 */
  private static final List<String> DROP_STATEMENTS =
      List.of(
          """
          drop table if exists
              canvas_function_resource_pin,
              canvas_function_run,
              canvas_resource,
              canvas_link,
              canvas_node,
              canvas_group,
              canvas_command_dedup,
              canvas_document
          cascade
          """);

  /** {@code docs/canvas-project.sql} 的 canvas 目标定义。 */
  private static final List<String> CREATE_STATEMENTS =
      List.of(
          """
          create table canvas_document (
              id uuid primary key,
              title varchar(256) not null check (btrim(title) <> ''),
              revision bigint not null default 0 check (revision >= 0),
              created_at timestamptz(3) not null default current_timestamp,
              updated_at timestamptz(3) not null default current_timestamp
          )
          """,
          "create index idx_canvas_document_updated on canvas_document (updated_at, id)",
          """
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
          )
          """,
          """
          create table canvas_node (
              id uuid primary key,
              canvas_id uuid not null references canvas_document (id) on delete restrict,
              name varchar(256) not null check (btrim(name) <> '' and name = btrim(name)),
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
              constraint ck_canvas_node_function check (
                  "function" is null
                  or (
                      jsonb_typeof("function") = 'object'
                      and coalesce(jsonb_typeof("function" -> 'name'), '') = 'string'
                      and coalesce(btrim("function" ->> 'name'), '') <> ''
                      and coalesce(jsonb_typeof("function" -> 'args'), '') = 'object'
                  )
              )
          )
          """,
          "create index idx_canvas_node_group on canvas_node (canvas_id, group_id)"
              + " where group_id is not null",
          """
          create table canvas_resource (
              id uuid primary key,
              canvas_id uuid not null references canvas_document (id) on delete restrict,
              owner_node_id uuid,
              resource_index integer,
              name varchar(512) not null check (btrim(name) <> ''),
              blob_id uuid references storage_blob (id) on delete restrict,
              text_content text,
              created_at timestamptz(3) not null default current_timestamp,
              constraint uk_canvas_resource_canvas_id unique (canvas_id, id),
              constraint uk_canvas_resource_slot unique (canvas_id, owner_node_id, resource_index),
              constraint fk_canvas_resource_owner foreign key (canvas_id, owner_node_id)
                  references canvas_node (canvas_id, id) on delete restrict,
              constraint ck_canvas_resource_owner_pair check (
                  (owner_node_id is null) = (resource_index is null)
              ),
              constraint ck_canvas_resource_index check (resource_index is null or resource_index >= 0),
              constraint ck_canvas_resource_content check ((blob_id is null) <> (text_content is null))
          )
          """,
          "create index idx_canvas_resource_blob on canvas_resource (blob_id) where blob_id is not null",
          "create index idx_canvas_resource_created on canvas_resource (canvas_id, created_at, id)",
          """
          create table canvas_function_run (
              node_id uuid primary key references canvas_node (id) on delete restrict,
              request_id uuid not null,
              status varchar(16) not null,
              attempt integer not null default 0 check (attempt >= 0),
              available_at timestamptz(3),
              lease_token varchar(128),
              lease_until timestamptz(3),
              state_json jsonb not null check (jsonb_typeof(state_json) = 'object'),
              error text,
              created_at timestamptz(3) not null default current_timestamp,
              updated_at timestamptz(3) not null default current_timestamp,
              unique (node_id, request_id),
              check (status in ('READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
              constraint ck_canvas_function_run_lease_pair check ((lease_token is null) = (lease_until is null)),
              check (lease_token is null or btrim(lease_token) <> ''),
              constraint ck_canvas_function_run_status_shape check (
                  (status = 'READY' and available_at is not null and lease_token is null)
                  or (status = 'RUNNING' and available_at is null and lease_token is not null)
                  or (status in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
                      and available_at is null and lease_token is null)
              ),
              check (status not in ('FAILED', 'UNKNOWN') or (error is not null and btrim(error) <> ''))
          )
          """,
          """
          create index idx_canvas_function_run_claim
              on canvas_function_run (status, available_at, lease_until, created_at, node_id)
              where status in ('READY', 'RUNNING')
          """,
          """
          create table canvas_function_resource_pin (
              canvas_id uuid not null,
              node_id uuid not null,
              request_id uuid not null,
              role varchar(16) not null check (role in ('INPUT', 'OUTPUT')),
              resource_id uuid not null,
              primary key (canvas_id, node_id, request_id, role, resource_id),
              constraint fk_canvas_pin_node foreign key (canvas_id, node_id)
                  references canvas_node (canvas_id, id) on delete restrict,
              constraint fk_canvas_pin_run foreign key (node_id, request_id)
                  references canvas_function_run (node_id, request_id) on delete restrict,
              constraint fk_canvas_pin_resource foreign key (canvas_id, resource_id)
                  references canvas_resource (canvas_id, id) on delete restrict
          )
          """,
          """
          create index idx_canvas_function_pin_resource
              on canvas_function_resource_pin (canvas_id, resource_id)
          """,
          """
          create table canvas_command_dedup (
              canvas_id uuid not null references canvas_document (id) on delete restrict,
              idempotency_key uuid not null,
              request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
              accepted_revision bigint not null,
              constraint pk_canvas_command_dedup primary key (canvas_id, idempotency_key),
              constraint ck_canvas_command_dedup_revision check (accepted_revision >= 0)
          )
          """,
          // 目标 DDL 未重述既有 NOTIFY trigger；重建 canvas_function_run 时必须恢复，否则 Runtime 的提交即唤醒契约在测试中消失。
          """
          create or replace function canvas_function_work_notify()
          returns trigger language plpgsql as $$
          begin
              if new.status = 'READY'
                  and new.lease_token is null
                  and new.available_at <= current_timestamp then
                  perform pg_notify('canvas_function_work', '');
              end if;
              return new;
          end $$;
          """,
          """
          create trigger trg_canvas_function_work_notify
              after insert or update on canvas_function_run
              for each row execute function canvas_function_work_notify()
          """);

  private CanvasTargetSchemaFixture() {}

  /** 在 baseline schema 之上重建 canvas 目标表；仅限 {@link #TEST_DATABASE} 隔离测试库。 */
  static void apply(Connection connection) throws SQLException {
    requireTestDatabase(connection);
    try (Statement statement = connection.createStatement()) {
      for (String sql : DROP_STATEMENTS) {
        statement.execute(sql);
      }
      for (String sql : CREATE_STATEMENTS) {
        statement.execute(sql);
      }
    }
  }

  /** 校验当前连接指向隔离测试库，使重建 canvas 表与级联删除不可能落到部署库。 */
  private static void requireTestDatabase(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("select current_database()")) {
      if (!resultSet.next() || !TEST_DATABASE.equals(resultSet.getString(1))) {
        throw new IllegalStateException(
            "canvas target schema fixture requires the isolated test database: " + TEST_DATABASE);
      }
    }
  }
}
