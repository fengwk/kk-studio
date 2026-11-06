import type { SystemSettingsDTO } from '@/shared/api/generated/contracts'

/**
 * GET/PUT 的公共 section 载体（tool / aiRuntime / environment / network / storageMedia / advanced）。
 *
 * <p>Java 的 `SystemSettingsSectionsDTO` 基类被生成器展平进聚合 DTO；这里只从生成类型去掉版本与时间戳，
 * 不重复声明 section 字段。
 */
export type SystemSettingsSectionsDTO = Omit<
  SystemSettingsDTO,
  'version' | 'createdAt' | 'updatedAt'
>
