import { useMutation, useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query'

async function invalidateQueryKeyList(queryClient: QueryClient, keys: QueryKey[]) {
  await Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
}

export function useInvalidateMutation<TData, TVariables>({
  mutationFn,
  invalidateQueryKeys,
  onSuccess,
}: {
  mutationFn: (variables: TVariables) => Promise<TData>
  invalidateQueryKeys: QueryKey[]
  onSuccess?: (data: TData) => void | Promise<void>
}) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: async (data) => {
      await invalidateQueryKeyList(queryClient, invalidateQueryKeys)
      await onSuccess?.(data)
    },
  })
}
