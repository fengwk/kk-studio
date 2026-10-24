#!/usr/bin/env node
/**
 * Validate the sole production Flyway baseline in an owned, network-isolated PostgreSQL container.
 *
 * The runner loads exactly one versioned migration, `schema/src/main/resources/db/migration/V1__schema.sql`,
 * which is the single authoritative definition of the product schema: there is no second design DDL to keep
 * in sync. It then proves three kinds of target facts:
 *
 * - the business and infrastructure tables of that baseline are present and nothing else was added;
 * - the IDE-routing / Canvas / Project / Chat-Session target tables expose exactly their declared
 *   columns, foreign-key relations and indexes;
 * - the shared contract probes (`fresh-install-probes.sql`, also executed by the schema module's Java
 *   contract test) reject invalid writes, with at least 135 positive and negative assertions.
 *
 * Negative probes mutate the loaded target schema inside a rolled-back transaction and require the validator
 * to report exactly the mutated object, so a silently broken check (missing FK, index or column) cannot pass.
 *
 * Isolation: the container is created from a local image on the local Docker unix socket only, never joins a
 * network, never publishes a host port, uses a tmpfs data directory and is removed on exit. Deployment
 * database connection variables are never read.
 */

import { spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

import { repositoryRoot } from '../../lib/repo-root.mjs'

const FIELD_SEPARATOR = '\x1f'

export const MIGRATION_DIRECTORY = 'schema/src/main/resources/db/migration'
export const BASELINE_FILE = 'V1__schema.sql'
// Single copy of the positive/negative SQL probes, shared with the schema module's Java
// contract test; the repository keeps no second copy of them.
const PROBE_FILE = 'schema/src/test/resources/fun/fengwk/kkstudio/schema/fresh-install-probes.sql'
const MINIMUM_PROBE_ASSERTIONS = 135
const IMAGE = 'postgres:17.10'

// The business and infrastructure tables of the sole production baseline. Flyway adds its own
// history table on top, so the accepted table set is exactly this list. `main` re-derives the set
// from the baseline DDL and fails on any drift, so a new or removed table can never slip through
// silently.
const BUSINESS_TABLES = [
  'agent_definition',
  'agent_model',
  'agent_model_group_cooldown',
  'agent_provider',
  'canvas_command_dedup',
  'canvas_document',
  'canvas_function_resource_pin',
  'canvas_function_run',
  'canvas_group',
  'canvas_node',
  'canvas_resource',
  'chat',
  'chat_session',
  'cluster_node',
  'durable_work',
  'environment',
  'environment_connection',
  'environment_install_code',
  'environment_update_operation',
  'harness_entry',
  'harness_execution_exit',
  'harness_model_invocation',
  'harness_session',
  'harness_thread',
  'harness_thread_command',
  'harness_thread_join',
  'harness_thread_stop_receipt',
  'harness_tool_invocation',
  'mcp_server',
  'mcp_tool',
  'plugin_auth_attempt',
  'plugin_configuration',
  'plugin_credential',
  'project',
  'project_issue',
  'project_issue_activity',
  'project_issue_agent_thread',
  'project_issue_evidence',
  'project_issue_run',
  'project_issue_stage_budget',
  'session_blob_ref',
  'skill_package',
  'storage_blob',
  'storage_object_cleanup',
  'storage_upload',
  'system_setting'
]

// The target tables the IDE routing / Canvas / Project rewrite installs; only these are
// checked down to their columns, relations and indexes.
const TARGET_TABLES = [
  'canvas_command_dedup',
  'canvas_document',
  'canvas_function_resource_pin',
  'canvas_function_run',
  'canvas_group',
  'canvas_node',
  'canvas_resource',
  'chat_session',
  'cluster_node',
  'project',
  'project_issue',
  'project_issue_activity',
  'project_issue_agent_thread',
  'project_issue_evidence',
  'project_issue_run',
  'project_issue_stage_budget',
  'durable_work'
]

const TARGET_COLUMNS = {
  'canvas_command_dedup': [
    'canvas_id',
    'idempotency_key',
    'request_hash',
    'accepted_revision'
  ],
  'canvas_document': [
    'id',
    'title',
    'revision',
    'created_at',
    'updated_at'
  ],
  'canvas_function_resource_pin': [
    'canvas_id',
    'node_id',
    'request_id',
    'role',
    'resource_id'
  ],
  'canvas_function_run': [
    'node_id',
    'request_id',
    'status',
    'attempt',
    'state_json',
    'error',
    'created_at',
    'updated_at'
  ],
  'canvas_group': [
    'id',
    'canvas_id',
    'title',
    'x',
    'y',
    'width',
    'height'
  ],
  'canvas_node': [
    'id',
    'canvas_id',
    'name',
    'name_key',
    'x',
    'y',
    'width',
    'height',
    'group_id',
    'function'
  ],
  'canvas_resource': [
    'id',
    'canvas_id',
    'owner_node_id',
    'resource_index',
    'name',
    'blob_id',
    'text_content',
    'created_at'
  ],
  'chat_session': [
    'session_id',
    'chat_id',
    'created_at'
  ],
  'cluster_node': [
    'instance_id',
    'internal_url',
    'lease_until',
    'last_seen'
  ],
  'project': [
    'id',
    'title',
    'description',
    'workflow',
    'yolo_enabled',
    'next_issue_number',
    'version',
    'archived_at',
    'created_at',
    'updated_at'
  ],
  'project_issue': [
    'id',
    'project_id',
    'number',
    'title',
    'description',
    'state',
    'blocked_from_state',
    'block_reason',
    'pause_reason',
    'pause_detail',
    'next_run_ordinal',
    'next_activity_sequence',
    'version',
    'archived_at',
    'created_at',
    'updated_at'
  ],
  'project_issue_activity': [
    'issue_id',
    'sequence',
    'kind',
    'actor_type',
    'actor_agent_name',
    'run_id',
    'body',
    'data',
    'idempotency_key',
    'request_hash',
    'created_at'
  ],
  'project_issue_agent_thread': [
    'issue_id',
    'agent_name',
    'thread_id',
    'created_at'
  ],
  'project_issue_evidence': [
    'issue_id',
    'blob_id',
    'actor_agent_name',
    'run_id',
    'name',
    'created_at'
  ],
  'project_issue_run': [
    'id',
    'issue_id',
    'ordinal',
    'state',
    'session_id',
    'thread_id',
    'status',
    'start_entry_id',
    'end_entry_id',
    'final_answer_entry_id',
    'next_state',
    'observed_activity_sequence',
    'remaining_execution_ms',
    'active_since',
    'error',
    'version',
    'started_at',
    'ended_at'
  ],
  'project_issue_stage_budget': [
    'issue_id',
    'state',
    'max_runs',
    'budget_after_ordinal',
    'created_at',
    'updated_at'
  ],
  'durable_work': [
    'target_type',
    'target_id',
    'available_at',
    'wake_version',
    'lease_token',
    'lease_until',
    'required_environment_id'
  ]
}

const TARGET_RELATIONS = {
  'canvas_command_dedup': [
    'canvas_command_dedup_canvas_id_fkey'
  ],
  'canvas_document': [],
  'canvas_function_resource_pin': [
    'fk_canvas_pin_node',
    'fk_canvas_pin_resource',
    'fk_canvas_pin_run'
  ],
  'canvas_function_run': [
    'canvas_function_run_node_id_fkey'
  ],
  'canvas_group': [
    'canvas_group_canvas_id_fkey'
  ],
  'canvas_node': [
    'canvas_node_canvas_id_fkey',
    'fk_canvas_node_group'
  ],
  'canvas_resource': [
    'canvas_resource_canvas_id_fkey',
    'fk_canvas_resource_blob',
    'fk_canvas_resource_owner'
  ],
  'chat_session': [
    'fk_chat_session_chat',
    'fk_chat_session_session'
  ],
  'cluster_node': [],
  'project': [],
  'project_issue': [
    'project_issue_project_id_fkey'
  ],
  'project_issue_activity': [
    'fk_project_issue_activity_agent',
    'fk_project_issue_activity_issue',
    'fk_project_issue_activity_run'
  ],
  'project_issue_agent_thread': [
    'fk_project_issue_agent_thread_agent',
    'fk_project_issue_agent_thread_issue',
    'fk_project_issue_agent_thread_thread'
  ],
  'project_issue_evidence': [
    'fk_project_issue_evidence_agent',
    'fk_project_issue_evidence_blob',
    'fk_project_issue_evidence_issue',
    'fk_project_issue_evidence_run'
  ],
  'project_issue_run': [
    'fk_project_issue_run_agent_thread',
    'fk_project_issue_run_end_entry',
    'fk_project_issue_run_final_answer',
    'fk_project_issue_run_stage_budget',
    'fk_project_issue_run_start_entry',
    'fk_project_issue_run_thread_session'
  ],
  'project_issue_stage_budget': [
    'fk_project_issue_stage_budget_issue'
  ],
  'durable_work': [
    'fk_durable_work_environment'
  ]
}

const TARGET_INDEXES = {
  'canvas_command_dedup': [
    'pk_canvas_command_dedup'
  ],
  'canvas_document': [
    'canvas_document_pkey',
    'idx_canvas_document_updated'
  ],
  'canvas_function_resource_pin': [
    'canvas_function_resource_pin_pkey',
    'idx_canvas_function_pin_resource'
  ],
  'canvas_function_run': [
    'canvas_function_run_node_id_request_id_key',
    'canvas_function_run_pkey'
  ],
  'canvas_group': [
    'canvas_group_canvas_id_id_key',
    'canvas_group_pkey'
  ],
  'canvas_node': [
    'canvas_node_pkey',
    'idx_canvas_node_group',
    'uk_canvas_node_canvas_id',
    'uk_canvas_node_canvas_name_key'
  ],
  'canvas_resource': [
    'canvas_resource_pkey',
    'idx_canvas_resource_blob',
    'idx_canvas_resource_created',
    'uk_canvas_resource_canvas_id',
    'uk_canvas_resource_slot'
  ],
  'chat_session': [
    'idx_chat_session_chat',
    'pk_chat_session'
  ],
  'cluster_node': [
    'cluster_node_pkey'
  ],
  'project': [
    'idx_project_updated',
    'project_pkey'
  ],
  'project_issue': [
    'idx_project_issue_board',
    'project_issue_pkey',
    'project_issue_project_id_number_key'
  ],
  'project_issue_activity': [
    'idx_project_activity_run',
    'pk_project_issue_activity',
    'uk_project_activity_run',
    'uk_project_issue_activity_request'
  ],
  'project_issue_agent_thread': [
    'pk_project_issue_agent_thread',
    'uk_project_issue_agent_thread_issue',
    'uk_project_issue_agent_thread_thread'
  ],
  'project_issue_evidence': [
    'idx_project_issue_evidence_blob',
    'pk_project_issue_evidence'
  ],
  'project_issue_run': [
    'idx_project_issue_run_budget',
    'idx_project_issue_run_session',
    'project_issue_run_pkey',
    'uk_project_issue_run_active',
    'uk_project_issue_run_id_issue',
    'uk_project_issue_run_issue_ordinal'
  ],
  'project_issue_stage_budget': [
    'pk_project_issue_stage_budget'
  ],
  'durable_work': [
    'durable_work_pkey',
    'idx_durable_work_available',
    'idx_durable_work_lease_until'
  ]
}

// Negative evidence: each mutation is rolled back, so the validated container state is
// untouched, yet the validator must report exactly the mutated object and nothing else.
const NEGATIVE_PROBES = [
  [
    'a dropped target foreign key',
    'alter table project_issue_run drop constraint fk_project_issue_run_stage_budget;',
    [
      [
        'missing relation',
        'project_issue_run.fk_project_issue_run_stage_budget'
      ]
    ]
  ],
  [
    'a dropped target index',
    'drop index idx_project_issue_run_budget;',
    [
      [
        'missing index',
        'project_issue_run.idx_project_issue_run_budget'
      ]
    ]
  ],
  [
    'a dropped target column',
    'alter table project_issue_run drop column remaining_execution_ms;',
    [
      [
        'missing column',
        'project_issue_run.remaining_execution_ms'
      ]
    ]
  ],
  [
    'an added target column',
    'alter table durable_work add column probe_field text;',
    [
      [
        'unexpected column',
        'durable_work.probe_field'
      ]
    ]
  ],
  [
    'an added table',
    'create table probe_table (id uuid primary key);',
    [
      [
        'unexpected table',
        'probe_table'
      ]
    ]
  ]
]

export { BUSINESS_TABLES, TARGET_COLUMNS, TARGET_INDEXES, TARGET_RELATIONS, TARGET_TABLES }

const TABLE_DECLARATION = /^\s*create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_][a-z0-9_]*)/gim

/** Return the sorted table names the baseline DDL creates, ignoring SQL line comments. */
export function declaredBaselineTables(baselineSql) {
  const withoutComments = baselineSql.replace(/--[^\n]*/g, '')
  return [...withoutComments.matchAll(TABLE_DECLARATION)].map((match) => match[1]).sort()
}

/**
 * Return the declared inventory's drift against the baseline DDL as two sorted lists.
 *
 * The first list holds baseline tables that are missing from `BUSINESS_TABLES` (a silently
 * ignored new table); the second holds declared tables the baseline never creates (a stale entry).
 */
export function inventoryMismatch(baselineSql) {
  const declared = new Set(declaredBaselineTables(baselineSql))
  const inventory = new Set(BUSINESS_TABLES)
  return [
    [...declared].filter((name) => !inventory.has(name)).sort(),
    [...inventory].filter((name) => !declared.has(name)).sort(),
  ]
}

/** Render literal rows as a SQL `values` list. */
function values(rows) {
  return rows.map((row) => '(' + row.map((value) => `'${value}'`).join(', ') + ')').join(', ')
}

const sorted = (list) => [...list].sort()
const flatten = (map) =>
  sorted(Object.keys(map)).flatMap((table) => map[table].map((name) => [table, name]))

/** Return every structural difference between the loaded baseline and the target contract. */
export function violationsQuery() {
  const tables = values(sorted(BUSINESS_TABLES).map((name) => [name]))
  const targetTables = values(sorted(TARGET_TABLES).map((name) => [name]))
  const columns = values(flatten(TARGET_COLUMNS))
  const relations = values(flatten(TARGET_RELATIONS))
  const indexes = values(flatten(TARGET_INDEXES))
  return `
        with expected_table(name) as (values ${tables}),
            target_table(name) as (values ${targetTables}),
            expected_column(table_name, column_name) as (values ${columns}),
            expected_relation(table_name, constraint_name) as (values ${relations}),
            expected_index(table_name, index_name) as (values ${indexes}),
            actual_table as (
                select tablename from pg_tables where schemaname = 'public'
            ),
            target_column as (
                select c.table_name, c.column_name
                    from information_schema.columns c
                    join target_table t on t.name = c.table_name
                    where c.table_schema = 'public'
            ),
            target_relation as (
                select con.conrelid::regclass::text as table_name, con.conname
                    from pg_constraint con
                    join target_table t on t.name = con.conrelid::regclass::text
                    where con.contype = 'f' and con.connamespace = 'public'::regnamespace
            ),
            target_index as (
                select i.tablename as table_name, i.indexname
                    from pg_indexes i
                    join target_table t on t.name = i.tablename
                    where i.schemaname = 'public'
            )
        select problem, object from (
            select 'missing table' as problem, e.name as object
                from expected_table e
                where not exists (select 1 from actual_table a where a.tablename = e.name)
            union all
            select 'unexpected table', a.tablename
                from actual_table a
                where not exists (select 1 from expected_table e where e.name = a.tablename)
            union all
            select 'missing column', e.table_name || '.' || e.column_name
                from expected_column e
                where not exists (
                    select 1 from target_column a
                        where a.table_name = e.table_name and a.column_name = e.column_name)
            union all
            select 'unexpected column', a.table_name || '.' || a.column_name
                from target_column a
                where not exists (
                    select 1 from expected_column e
                        where e.table_name = a.table_name and e.column_name = a.column_name)
            union all
            select 'missing relation', e.table_name || '.' || e.constraint_name
                from expected_relation e
                where not exists (
                    select 1 from target_relation a
                        where a.table_name = e.table_name and a.conname = e.constraint_name)
            union all
            select 'unexpected relation', a.table_name || '.' || a.conname
                from target_relation a
                where not exists (
                    select 1 from expected_relation e
                        where e.table_name = a.table_name and e.constraint_name = a.conname)
            union all
            select 'missing index', e.table_name || '.' || e.index_name
                from expected_index e
                where not exists (
                    select 1 from target_index a
                        where a.table_name = e.table_name and a.indexname = e.index_name)
            union all
            select 'unexpected index', a.table_name || '.' || a.indexname
                from target_index a
                where not exists (
                    select 1 from expected_index e
                        where e.table_name = a.table_name and e.index_name = a.indexname)
        ) violations
        order by problem, object;
    `
}

/** Parse psql rows (`problem<US>object`) into a Set of `problem object` keys. */
export function parseViolations(output) {
  const violations = new Set()
  for (const line of output.split(/\r?\n/)) {
    if (!line.trim()) {
      continue
    }
    const fields = line.split(FIELD_SEPARATOR)
    if (fields.length !== 2) {
      throw new Error(`unexpected violation row shape: ${JSON.stringify(line)}`)
    }
    violations.add(`${fields[0]} ${fields[1]}`)
  }
  return violations
}

const describe = (violations) => [...violations].sort()

function sameSet(left, right) {
  return left.size === right.size && [...left].every((item) => right.has(item))
}

function main() {
  const root = repositoryRoot()
  const migrationDirectory = path.join(root, MIGRATION_DIRECTORY)
  const baselines = readdirSync(migrationDirectory)
    .filter((name) => /^V.*\.sql$/.test(name))
    .sort()
  if (baselines.length !== 1 || baselines[0] !== BASELINE_FILE) {
    throw new Error(
      `the production schema must be the single ${BASELINE_FILE}; found ${JSON.stringify(baselines)}`,
    )
  }
  const baselineSql = readFileSync(path.join(migrationDirectory, BASELINE_FILE), 'utf8')
  const probes = path.join(root, PROBE_FILE)
  if (!existsSync(probes) || !statSync(probes).isFile()) {
    throw new Error(`missing shared contract probes: ${PROBE_FILE}`)
  }
  if (BUSINESS_TABLES.length !== 45 || TARGET_TABLES.length !== 17) {
    throw new Error('the declared inventory must keep its fixed business and target table counts')
  }
  // Re-derive the inventory from the baseline DDL: a table added to (or removed from) the
  // baseline without updating BUSINESS_TABLES must fail before the isolated database runs.
  const [missingFromInventory, notCreatedByBaseline] = inventoryMismatch(baselineSql)
  if (missingFromInventory.length > 0 || notCreatedByBaseline.length > 0) {
    throw new Error(
      'declared table inventory drifted from the baseline DDL: ' +
        `baseline tables missing from the inventory ${JSON.stringify(missingFromInventory)}, ` +
        `declared tables the baseline does not create ${JSON.stringify(notCreatedByBaseline)}`,
    )
  }

  // Force the local Unix socket; never inherit a deployment Docker context or PG URL.
  const socket = '/var/run/docker.sock'
  if (!existsSync(socket)) {
    throw new Error('requires the local /var/run/docker.sock')
  }
  const environment = Object.fromEntries(
    Object.entries(process.env).filter(
      ([key]) => !['DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'].includes(key),
    ),
  )

  const command = (args, { text, check = true, timeout = 120_000 } = {}) => {
    const result = spawnSync('docker', ['--host', `unix://${socket}`, ...args], {
      input: text,
      encoding: 'utf8',
      env: environment,
      timeout,
      maxBuffer: 1 << 28,
    })
    if (result.error) {
      throw result.error
    }
    if (check && result.status) {
      throw new Error(result.stderr.trim() || 'local Docker command failed')
    }
    return result
  }

  command(['image', 'inspect', IMAGE])
  const suffix = randomBytes(16).toString('hex')
  const name = `kkstudio-v1-${suffix}`
  const database = `kkstudio_v1_${suffix}`
  const label = 'kkstudio.schema-baseline'
  let containerId = null
  let cleanupError = null
  try {
    containerId = command([
      'run',
      '--detach',
      '--rm',
      '--pull=never',
      '--name',
      name,
      '--label',
      `${label}=${suffix}`,
      '--network',
      'none',
      '--memory',
      '512m',
      '--cpus',
      '1',
      '--tmpfs',
      '/var/lib/postgresql/data:rw,size=256m',
      '--env',
      'POSTGRES_HOST_AUTH_METHOD=trust',
      '--env',
      `POSTGRES_DB=${database}`,
      IMAGE,
    ]).stdout.trim()
    let ready = false
    for (let attempt = 0; attempt < 60 && !ready; attempt += 1) {
      ready =
        command(['exec', containerId, 'pg_isready', '-U', 'postgres', '-d', database], {
          check: false,
          timeout: 10_000,
        }).status === 0
      if (!ready) {
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500)
      }
    }
    if (!ready) {
      throw new Error('isolated PostgreSQL did not become ready')
    }

    // The container this script owns must be unreachable: no network, no host port.
    const network = command(['inspect', '--format', '{{.HostConfig.NetworkMode}}', containerId]).stdout.trim()
    const published = command(['inspect', '--format', '{{len .NetworkSettings.Ports}}', containerId]).stdout.trim()
    if (network !== 'none' || published !== '0') {
      throw new Error(`container is not isolated: network=${network} publishedPorts=${published}`)
    }

    const sql = (source, target = database) =>
      command(
        [
          'exec',
          '-i',
          containerId,
          'psql',
          '-X',
          '-q',
          '-A',
          '-t',
          '-F',
          FIELD_SEPARATOR,
          '-v',
          'ON_ERROR_STOP=1',
          '-U',
          'postgres',
          '-d',
          target,
        ],
        { text: source },
      ).stdout

    sql(baselineSql)
    const violations = parseViolations(sql(violationsQuery()))
    if (violations.size > 0) {
      throw new Error(`baseline structure mismatch: ${JSON.stringify(describe(violations))}`)
    }
    console.log(`PASS sole production ${BASELINE_FILE}: ${BUSINESS_TABLES.length} business tables present, no extra table`)
    console.log(`PASS ${TARGET_TABLES.length} target tables expose their declared columns, relations and indexes`)

    const probeResult = command(
      ['exec', '-i', containerId, 'psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', database],
      { text: readFileSync(probes, 'utf8') },
    )
    const marker = /PASS (\d+) database contract assertions/.exec(probeResult.stdout)
    if (!marker) {
      throw new Error('probe run reported no assertion count:\n' + probeResult.stdout.trim())
    }
    const reported = Number.parseInt(marker[1], 10)
    if (reported < MINIMUM_PROBE_ASSERTIONS) {
      throw new Error(
        `probe run reported only ${reported} assertions, expected at least ${MINIMUM_PROBE_ASSERTIONS}`,
      )
    }
    console.log(`PASS ${reported} positive and negative database contract assertions`)

    for (const [labelText, mutation, expectedRows] of NEGATIVE_PROBES) {
      const expected = new Set(expectedRows.map(([problem, object]) => `${problem} ${object}`))
      const probed = parseViolations(sql(['begin;', mutation, violationsQuery(), 'rollback;'].join('\n')))
      if (!sameSet(probed, expected)) {
        throw new Error(
          `validator missed ${labelText}: expected ${JSON.stringify(describe(expected))}, got ${JSON.stringify(describe(probed))}`,
        )
      }
    }
    console.log(
      `PASS validator detects ${NEGATIVE_PROBES.length} non-declared schema changes ` +
        '(missing FK, index and column, added column and table)',
    )
    if (parseViolations(sql(violationsQuery())).size > 0) {
      throw new Error('rolled-back negative probes changed the validated baseline')
    }
    console.log('PASS rolled-back probes left the validated baseline unchanged')
  } finally {
    // A timed-out `docker run` may have created the container before returning its ID.
    if (!containerId) {
      const owned = command(['inspect', '--format', `{{index .Config.Labels "${label}"}}`, name], {
        check: false,
      })
      if (owned.status === 0 && owned.stdout.trim() === suffix) {
        containerId = name
      }
    }
    if (containerId) {
      const removal = command(['rm', '--force', containerId], { check: false })
      if (removal.status) {
        cleanupError = removal.stderr.trim()
      }
    }
    if (cleanupError) {
      throw new Error(`owned container cleanup failed (${name}): ${cleanupError}`)
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    main()
  } catch (error) {
    process.stderr.write(`FAIL: ${error.message}\n`)
    process.exitCode = 1
  }
}
