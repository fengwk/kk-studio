import type {
  SystemSettingsAdvancedDTO,
  SystemSettingsAiRuntimeDTO,
  SystemSettingsEnvironmentDTO,
  SystemSettingsNetworkDTO,
  SystemSettingsStorageMediaDTO,
  SystemSettingsToolDTO,
} from '@/shared/api/generated/contracts'

/** GET/PUT 的公共 section 载体（tool / aiRuntime / environment / network / storageMedia / advanced）。 */
export interface SystemSettingsSectionsDTO {
  tool: SystemSettingsToolDTO
  aiRuntime: SystemSettingsAiRuntimeDTO
  environment: SystemSettingsEnvironmentDTO
  network: SystemSettingsNetworkDTO
  storageMedia: SystemSettingsStorageMediaDTO
  advanced: SystemSettingsAdvancedDTO
}

