import type {
  DaemonConfigDTO,
  EnvironmentDTO,
  EnvironmentRuntimeDTO,
  EnvironmentRuntimeStatusDTO,
  EnvironmentUpdateProgressDTO,
} from '@/shared/api/generated/contracts'

type RuntimeConfiguration = NonNullable<EnvironmentRuntimeDTO['configuration']>

/** 完整卡片默认配置：studioUrl 必填，其余可空；proxy null 表示直连。 */
export function testDaemonConfig(overrides: Partial<DaemonConfigDTO> = {}): DaemonConfigDTO {
  return {
    studioUrl: 'https://studio.example.com',
    bashExecutable: null,
    lsp: null,
    proxyUrl: null,
    ...overrides,
  }
}

export function testEnvironment(overrides: Partial<EnvironmentDTO> = {}): EnvironmentDTO {
  return {
    id: 'env-1',
    name: 'env',
    note: null,
    daemonConfig: testDaemonConfig(),
    status: 'READY',
    ready: true,
    statusExpiresAt: null,
    lastSeenAt: null,
    capabilities: [],
    version: '1',
    createdAt: 1_784_505_600_000,
    updatedAt: 1_784_505_600_000,
    ...overrides,
  }
}

export interface TestRuntimeOptions {
  revision?: string
  daemonVersion?: string
  update?: EnvironmentUpdateProgressDTO | null
  configuration?: RuntimeConfiguration | null
}

/** 运行快照默认与卡片 version 1 对齐；各用例可显式构造旧版本、失败或未知状态。 */
export function testRuntimeStatus(options: TestRuntimeOptions = {}): EnvironmentRuntimeStatusDTO {
  return {
    revision: options.revision ?? '1',
    status: {
      daemonVersion: options.daemonVersion ?? '1.2.3',
      update: options.update ?? null,
      configuration:
        options.configuration === undefined
          ? { appliedVersion: '1', proxyUrl: null, error: null }
          : options.configuration,
    },
  }
}
