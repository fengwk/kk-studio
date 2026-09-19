import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { environmentService, DEFAULT_OPERATION_LIMIT } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type {
  EnvironmentCardDTO,
  EnvironmentOperationDTO,
  EnvironmentOperationStatus,
} from '@/shared/api/contracts/ai-environment'
import { EnvironmentHostSection } from '@/features/ai/environment/EnvironmentHostSection'
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

  const [cancelOpTarget, setCancelOpTarget] = useState<EnvironmentOperationDTO | null>(null)
  const [cancelOpError, setCancelOpError] = useState<string | null>(null)

  // Operations query with smart polling (~2s only while active)
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
        list: true,
      })
    }
  }, [operationsQuery.data, environment.id, queryClient])

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
        list: true,
      })
    },
    onError: (err: unknown) => {
      setCancelOpError(err instanceof Error ? err.message : String(err))
    },
  })

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
            <EnvironmentHostSection environment={environment} />

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
    </>
  )
}
