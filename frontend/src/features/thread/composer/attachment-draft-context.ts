import { createContext } from 'react'

/** Chat keeps the owning Composer mounted while its page-local attachment draft exists. */
export const AttachmentDraftContext = createContext<((hasAttachments: boolean) => void) | null>(null)
