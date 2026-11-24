import { Pencil } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'

export function EditButton({ name, onClick }: { name: string; onClick: () => void }) {
  const { t } = useI18n()
  const label = t('ai.catalog.action.edit')
  return <Button variant="secondary" size="compact" onClick={onClick} aria-label={`${label} ${name}`}>
    <Pencil aria-hidden="true" />{label}
  </Button>
}
