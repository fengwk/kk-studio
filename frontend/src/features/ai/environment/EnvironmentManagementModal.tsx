import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { environmentService, DEFAULT_OPERATION_LIMIT } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type {
  EnvironmentCardDTO,
  EnvironmentOperationDTO,
  EnvironmentOperationStatus,
  EnvironmentSkillSourceCreateDTO,
  EnvironmentSkillSourceDTO,
  EnvironmentSkillSourceUpdateDTO,
} from '@/shared/api/contracts/ai-environment'
import { SourceEditModal } from '@/features/ai/environment/SourceEditModal'
import { SourceActionModal, type SourceActionType } from '@/features/ai/environment/SourceActionModal'
import { EnvironmentSourcesSection } from '@/features/ai/environment/EnvironmentSourcesSection'
import { EnvironmentInventorySection } from '@/features/ai/environment/EnvironmentInventorySection'
import { EnvironmentOperationsSection } from '@/features/ai/environment/EnvironmentOperationsSection'
import { invalidateEnvironmentArtifacts } from '@/features/ai/environment/environment-invalidation'
import {
  isActiveOperationStatus,
  isTerminalOperationStatus,
} from '@/features/ai/environment/environment-utils'

export interface EnvironmentManagementModalProps {
  environment: EnvironmentCardDTO
  onClose: () => void
}

export function EnvironmentManagementModal({
  environment,
  onClose,
}: EnvironmentManagementModalProps) {
  const { t } = useI18n()
  const queryClient = useQueryClient()

  // Sub-modal states
  const [editSourceTarget, setEditSourceTarget] = useState<EnvironmentSkillSourceDTO | null | 'CREATE'>(null)
  const [editSourceError, setEditSourceError] = useState<string | null>(null)
  const [deleteSourceTarget, setDeleteSourceTarget] = useState<EnvironmentSkillSourceDTO | null>(null)
  const [deleteSourceError, setDeleteSourceError] = useState<string | null>(null)
  const [actionSourceTarget, setActionSourceTarget] = useState<{
    source: EnvironmentSkillSourceDTO
    action: SourceActionType
  } | null>(null)
  const [actionSourceError, setActionSourceError] = useState<string | null>(null)
  const [cancelOpTarget, setCancelOpTarget] = useState<EnvironmentOperationDTO | null>(null)
  const [cancelOpError, setCancelOpError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)

  // 1. Sources query
  const sourcesQuery = useQuery({
    queryKey: queryKeys.environments.skillSources(environment.id),
    queryFn: () => environmentService.listSkillSources(environment.id),
  })

  // 2. Inventory query & Inventory skills query
  const inventoryQuery = useQuery({
    queryKey: queryKeys.environments.inventory(environment.id),
    queryFn: () => environmentService.getInventory(environment.id),
  })

  const skillsQuery = useQuery({
    queryKey: queryKeys.environments.inventorySkills(environment.id, false),
    queryFn: () => environmentService.listInventorySkills(environment.id, false),
  })

  // 3. Operations query with smart polling (~2s only while active)
  const operationsQuery = useQuery({
    queryKey: queryKeys.environments.operations(environment.id, DEFAULT_OPERATION_LIMIT),
    queryFn: () => environmentService.listOperations(environment.id, DEFAULT_OPERATION_LIMIT),
    refetchInterval: (query) => {
      const list = query.state.data ?? []
      const hasActive = list.some((op) => isActiveOperationStatus(op.status))
      return hasActive ? 2000 : false
    },
    refetchIntervalInBackground: true,
  })

  // Detect terminal transitions to invalidate related caches
  const prevOpStatusesRef = useRef<Map<string, EnvironmentOperationStatus>>(new Map())
  useEffect(() => {
    const ops = operationsQuery.data ?? []
    let hadTerminalTransition = false
    const prevMap = prevOpStatusesRef.current

    for (const op of ops) {
      const prev = prevMap.get(op.id)
      if (prev && isActiveOperationStatus(prev) && isTerminalOperationStatus(op.status)) {
        hadTerminalTransition = true
      }
    }

    const nextMap = new Map<string, EnvironmentOperationStatus>()
    for (const op of ops) {
      nextMap.set(op.id, op.status)
    }
    prevOpStatusesRef.current = nextMap

    if (hadTerminalTransition) {
      void invalidateEnvironmentArtifacts(queryClient, environment.id, {
        sources: true,
        inventoryHeader: true,
        inventorySkills: true,
        list: true,
      })
    }
  }, [operationsQuery.data, environment.id, queryClient])

  // --- Source mutations ---
  const createSourceMutation = useMutation({
    mutationFn: (data: EnvironmentSkillSourceCreateDTO) =>
      environmentService.createSkillSource(environment.id, data),
    onSuccess: () => {
      setEditSourceTarget(null)
      setEditSourceError(null)
      void invalidateEnvironmentArtifacts(queryClient, environment.id, {
        sources: true,
        inventoryHeader: true,
        inventorySkills: true,
      })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setEditSourceTarget(null)
        setEditSourceError(null)
        return
      }
      setEditSourceError(err instanceof Error ? err.message : String(err))
    },
  })

  const updateSourceMutation = useMutation({
    mutationFn: ({ sourceId, data }: { sourceId: string; data: EnvironmentSkillSourceUpdateDTO }) =>
      environmentService.updateSkillSource(environment.id, sourceId, data),
    onSuccess: () => {
      setEditSourceTarget(null)
      setEditSourceError(null)
      void invalidateEnvironmentArtifacts(queryClient, environment.id, {
        sources: true,
        inventorySkills: true,
      })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setEditSourceTarget(null)
        setEditSourceError(null)
        return
      }
      setEditSourceError(err instanceof Error ? err.message : String(err))
    },
  })

  const deleteSourceMutation = useMutation({
    mutationFn: ({ sourceId, expectedVersion }: { sourceId: string; expectedVersion: string }) =>
      environmentService.deleteSkillSource(environment.id, sourceId, expectedVersion),
    onSuccess: () => {
      setDeleteSourceTarget(null)
      setDeleteSourceError(null)
      void invalidateEnvironmentArtifacts(queryClient, environment.id, {
        sources: true,
        inventoryHeader: true,
        inventorySkills: true,
      })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setDeleteSourceTarget(null)
        setDeleteSourceError(null)
        return
      }
      setDeleteSourceError(err instanceof Error ? err.message : String(err))
    },
  })

  // --- Operation action mutation ---
  const sourceActionMutation = useMutation({
    mutationFn: ({
      action,
      sourceId,
      timeoutMillis,
    }: {
      action: SourceActionType
      sourceId: string
      timeoutMillis: number
    }) => {
      const dto = { timeoutMillis }
      switch (action) {
        case 'REFRESH':
          return environmentService.requestSkillSourceRefresh(environment.id, sourceId, dto)
        case 'INSTALL':
          return environmentService.requestSkillSourceInstall(environment.id, sourceId, dto)
        case 'UPDATE':
          return environmentService.requestSkillSourceUpdate(environment.id, sourceId, dto)
      }
    },
    onSuccess: (returnedOp) => {
      setActionSourceTarget(null)
      setActionSourceError(null)

      // Record active status immediately so even on instant fast-completion terminal transition is never missed
      if (isActiveOperationStatus(returnedOp.status)) {
        prevOpStatusesRef.current.set(returnedOp.id, returnedOp.status)
      }

      // Seed/update operations cache with returned operation before invalidation (guarantees active state)
      queryClient.setQueryData<EnvironmentOperationDTO[]>(
        queryKeys.environments.operations(environment.id, DEFAULT_OPERATION_LIMIT),
        (old = []) => {
          const filtered = old.filter((item) => item.id !== returnedOp.id)
          return [returnedOp, ...filtered].slice(0, DEFAULT_OPERATION_LIMIT)
        },
      )

      void queryClient.invalidateQueries({
        queryKey: queryKeys.environments.operations(environment.id, DEFAULT_OPERATION_LIMIT),
      })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setActionSourceTarget(null)
        setActionSourceError(null)
        return
      }
      setActionSourceError(err instanceof Error ? err.message : String(err))
    },
  })

  // --- Cancel operation mutation ---
  const cancelOperationMutation = useMutation({
    mutationFn: (operationId: string) =>
      environmentService.cancelOperation(environment.id, operationId),
    onSuccess: (cancelledOp) => {
      setCancelOpTarget(null)
      setCancelOpError(null)

      queryClient.setQueryData<EnvironmentOperationDTO[]>(
        queryKeys.environments.operations(environment.id, DEFAULT_OPERATION_LIMIT),
        (old = []) => old.map((item) => (item.id === cancelledOp.id ? cancelledOp : item)),
      )

      void invalidateEnvironmentArtifacts(queryClient, environment.id, {
        operations: true,
        sources: true,
        inventoryHeader: true,
        inventorySkills: true,
        list: true,
      })
    },
    onError: (err: unknown) => {
      setCancelOpError(err instanceof Error ? err.message : String(err))
    },
  })

  function handleSourceSubmit(payload: {
    create?: EnvironmentSkillSourceCreateDTO
    update?: EnvironmentSkillSourceUpdateDTO
    sourceId?: string
  }) {
    if (payload.create) {
      createSourceMutation.mutate(payload.create)
    } else if (payload.update && payload.sourceId) {
      updateSourceMutation.mutate({
        sourceId: payload.sourceId,
        data: payload.update,
      })
    }
  }

  const sources = sourcesQuery.data ?? []
  const inventory = inventoryQuery.data
  const skills = skillsQuery.data ?? []
  const operations = operationsQuery.data ?? []

  return (
    <>
      <ModalBackdrop onClose={onClose}>
        <div
          className="modal-card env-management-modal-card"
          role="dialog"
          aria-modal="true"
          aria-label={`${t('ai.environment.managementTitle')} - ${environment.name}`}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <ModalHeader
            title={`${t('ai.environment.managementTitle')} - ${environment.name}`}
            onClose={onClose}
          />

          <div className="modal-body env-mgmt-body">
            <EnvironmentSourcesSection
              sources={sources}
              loading={sourcesQuery.isLoading}
              error={sourcesQuery.error ? String(sourcesQuery.error) : null}
              onAdd={() => {
                setEditSourceError(null)
                setEditSourceTarget('CREATE')
              }}
              onEdit={(source) => {
                setEditSourceError(null)
                setEditSourceTarget(source)
              }}
              onDelete={(source) => {
                setDeleteSourceError(null)
                setDeleteSourceTarget(source)
              }}
              onAction={(source, action) => {
                setActionSourceError(null)
                setActionSourceTarget({ source, action })
              }}
            />

            <EnvironmentInventorySection
              inventory={inventory}
              skills={skills}
              loading={inventoryQuery.isLoading || skillsQuery.isLoading}
              error={
                inventoryQuery.error
                  ? String(inventoryQuery.error)
                  : skillsQuery.error
                    ? String(skillsQuery.error)
                    : null
              }
            />

            <EnvironmentOperationsSection
              operations={operations}
              loading={operationsQuery.isLoading}
              error={operationsQuery.error ? String(operationsQuery.error) : null}
              onCancel={(op) => {
                setCancelOpError(null)
                setCancelOpTarget(op)
              }}
            />
          </div>
        </div>
      </ModalBackdrop>

      {/* Submodal: Create or Edit Source */}
      {editSourceTarget !== null && (
        <SourceEditModal
          source={editSourceTarget === 'CREATE' ? null : editSourceTarget}
          pending={createSourceMutation.isPending || updateSourceMutation.isPending}
          error={editSourceError}
          onClose={() => {
            setEditSourceTarget(null)
            setEditSourceError(null)
          }}
          onSubmit={handleSourceSubmit}
        />
      )}

      {/* Submodal: Source Action (Refresh/Install/Update) with editable timeoutMillis */}
      {actionSourceTarget !== null && (
        <SourceActionModal
          source={actionSourceTarget.source}
          action={actionSourceTarget.action}
          pending={sourceActionMutation.isPending}
          error={actionSourceError}
          onClose={() => {
            setActionSourceTarget(null)
            setActionSourceError(null)
          }}
          onConfirm={(timeoutMillis) => {
            sourceActionMutation.mutate({
              action: actionSourceTarget.action,
              sourceId: actionSourceTarget.source.sourceId,
              timeoutMillis,
            })
          }}
        />
      )}

      {/* Submodal: Delete Source confirmation */}
      {deleteSourceTarget !== null && (
        <ConfirmActionModal
          modal={{
            title: t('ai.environment.sources.delete'),
            description: t('ai.environment.sources.deleteConfirm'),
            confirmLabel: t('ai.environment.sources.delete'),
            tone: 'danger',
            error: deleteSourceError,
            onConfirm: () => {
              deleteSourceMutation.mutate({
                sourceId: deleteSourceTarget.sourceId,
                expectedVersion: deleteSourceTarget.version,
              })
            },
          }}
          pending={deleteSourceMutation.isPending}
          onClose={() => {
            setDeleteSourceTarget(null)
            setDeleteSourceError(null)
          }}
        />
      )}

      {/* Submodal: Cancel Operation confirmation */}
      {cancelOpTarget !== null && (
        <ConfirmActionModal
          modal={{
            title: t('ai.environment.operations.cancel'),
            description: t('ai.environment.operations.cancelConfirm', { id: cancelOpTarget.id }),
            confirmLabel: t('ai.environment.operations.cancel'),
            tone: 'danger',
            error: cancelOpError,
            onConfirm: () => {
              cancelOperationMutation.mutate(cancelOpTarget.id)
            },
          }}
          pending={cancelOperationMutation.isPending}
          onClose={() => {
            setCancelOpTarget(null)
            setCancelOpError(null)
          }}
        />
      )}

      {/* CAS Conflict presenter */}
      <ConflictPresenter
        conflict={conflict}
        onRefresh={() => {
          setConflict(null)
          setEditSourceTarget(null)
          setEditSourceError(null)
          setDeleteSourceTarget(null)
          setDeleteSourceError(null)
          setActionSourceTarget(null)
          setActionSourceError(null)
          setCancelOpTarget(null)
          setCancelOpError(null)
          void invalidateEnvironmentArtifacts(queryClient, environment.id, {
            sources: true,
            inventoryHeader: true,
            inventorySkills: true,
            operations: true,
            list: true,
          })
        }}
        onClose={() => setConflict(null)}
      />
    </>
  )
}
