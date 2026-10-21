/** 首次对话所需的 Catalog 资源是否已存在。 */
export interface CatalogSetupState {
  hasProvider: boolean
  hasModel: boolean
  hasAgent: boolean
}

export function isCatalogSetupComplete(state: CatalogSetupState): boolean {
  return state.hasProvider && state.hasModel && state.hasAgent
}
