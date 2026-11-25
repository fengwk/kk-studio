import { createContext, useContext } from 'react'
import type { StudioAccountDTO } from '@/shared/api/generated/contracts'

interface AccountContextValue {
  user: StudioAccountDTO | null
  refresh: () => Promise<void>
  signOut: () => Promise<void>
}

export const AccountContext = createContext<AccountContextValue | null>(null)

export function useAccount() {
  const account = useContext(AccountContext)
  if (!account) throw new Error('AccountProvider is required')
  return account
}
