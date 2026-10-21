import type { PropsWithChildren } from 'react'
import { CatalogRuntimeContext } from '@/features/catalog/CatalogRuntimeContext'
import {
  useCatalogPageController,
  type CatalogPageScope,
} from '@/features/catalog/useCatalogPageController'

export function CatalogRuntime({
  scope,
  children,
}: PropsWithChildren<{ scope: CatalogPageScope }>) {
  const controller = useCatalogPageController(scope)
  return (
    <CatalogRuntimeContext.Provider value={controller}>
      {children}
    </CatalogRuntimeContext.Provider>
  )
}
