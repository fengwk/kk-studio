/**
 * 由 Java Daemon 协议常量生成的 wire 契约；请勿手改。
 *
 * 生成器：harness/environment 测试源码 DaemonContractGenerator
 * 重新生成：scripts/dev/verify/generated-contracts/write.sh
 * 漂移门禁：DaemonContractArtifactTest（mvn verify 覆盖）
 */

/** 当前 Daemon wire 协议版本；其他版本一律拒绝，不做双协议 fallback。 */
export const PROTOCOL_VERSION = 7

/** capability catalog 版本；HELLO 上报，Platform 据此判定能否执行普通能力调用。 */
export const CAPABILITY_CATALOG_VERSION = '3'

/** READY capabilities 协议版本。 */
export const READY_CAPABILITIES_VERSION = 6

/** 结果 payload 的 UTF-8 上限（解码前与最终编码输出）。 */
export const MAX_PAYLOAD_UTF8_BYTES = 16777216

/** 单条控制消息（资源上传/更新）的字符上限：控制面只承载元数据。 */
export const MAX_CONTROL_PAYLOAD_CHARS = 16384

/** details JSON 与 json 内容的 UTF-8 上限。 */
export const MAX_DETAILS_JSON_UTF8_BYTES = 1048576

/** contents 数组元素上限。 */
export const MAX_CONTENT_ITEMS = 64

/** resource preview 的 UTF-8 上限。 */
export const MAX_PREVIEW_UTF8_BYTES = 16384

/** resource mediaType 的 ASCII 上限。 */
export const MAX_MEDIA_TYPE_ASCII_BYTES = 255

/** resource name 的 UTF-8 上限。 */
export const MAX_NAME_UTF8_BYTES = 512

/** 单条上传票据 FAILED 说明的字符上限。 */
export const MAX_FAILURE_MESSAGE_CHARS = 512

/** daemonVersion 的字符上限。 */
export const MAX_DAEMON_VERSION_CHARS = 64

/** capabilityId 的字符上限。 */
export const MAX_CAPABILITY_ID_CHARS = 128

/** Environment note 的字符上限。 */
export const MAX_NOTE_CHARS = 512

/** 预签名上传 URL 的字符上限。 */
export const MAX_PRESIGNED_URL_CHARS = 4096

/** 预签名上传请求头的条目上限。 */
export const MAX_PRESIGNED_HEADERS = 16

/** 预签名上传请求头名称与取值的字符上限。 */
export const MAX_PRESIGNED_HEADER_CHARS = 1024

/** UPDATE_RESULT 说明的字符上限。 */
export const MAX_UPDATE_MESSAGE_CHARS = 500

/** ERROR payload 的可选 code：目标 Environment 已有活跃租约，按退避重连。 */
export const ERROR_CODE_RETRY_LATER = 'RETRY_LATER'

/** ERROR payload 的可选 code：注册凭证无效，终态失败。 */
export const ERROR_CODE_REGISTRATION_REJECTED = 'REGISTRATION_REJECTED'

/** Daemon wire 协议的消息类型。 */
export const MESSAGE_TYPES = [
  'HELLO',
  'READY',
  'HEARTBEAT',
  'RUNTIME_STATUS',
  'STARTED',
  'PROGRESS',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'EXECUTION_EXITED',
  'WELCOME',
  'TEMPORARY_RESOURCE_POLICY',
  'INVOKE',
  'CANCEL',
  'RECOVER',
  'RESOURCE_UPLOAD_REQUEST',
  'RESOURCE_UPLOAD_TICKET',
  'RESOURCE_UPLOAD_COMMIT',
  'UPDATE',
  'UPDATE_RESULT',
  'IDE_COMMAND',
  'IDE_RESULT',
  'DATA_BIND',
  'IDE_LEASE',
  'ERROR',
] as const

export type MessageType = (typeof MESSAGE_TYPES)[number]

/** READY 上报的操作系统。 */
export const OPERATING_SYSTEMS = [
  'windows',
  'wsl',
  'linux',
  'macos',
] as const

export type OperatingSystem = (typeof OPERATING_SYSTEMS)[number]

/** READY 上报的 CPU 架构。 */
export const ARCHITECTURES = [
  'amd64',
  'arm64',
] as const

export type Architecture = (typeof ARCHITECTURES)[number]

/** 资源上传票据的状态。 */
export const TICKET_STATES = [
  'PENDING',
  'READY',
  'FAILED',
] as const

export type TicketState = (typeof TICKET_STATES)[number]

/** 受管更新命令上报的阶段。 */
export const UPDATE_PHASES = [
  'ACCEPTED',
  'PREPARED',
  'FAILED',
] as const

export type UpdatePhase = (typeof UPDATE_PHASES)[number]
