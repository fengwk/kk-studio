import { createContext, useContext } from 'react'
import type { CatalogPageController } from '@/features/ai/catalog/useCatalogPageController'

export const CatalogRuntimeContext =
  createContext<CatalogPageController | null>(null)

export function useCatalogRuntime() {
  const controller = useContext(CatalogRuntimeContext)
  if (!controller) {
    throw new Error('CatalogRuntime is required')
  }
  return controller
}

/** 供全局 ExtensionHost dialog contribution 读取当前 Catalog 页面状态。 */
export function useOptionalCatalogRuntime() {
  return useContext(CatalogRuntimeContext)
}
