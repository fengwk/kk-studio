import { screen, within, type BoundFunctions, type queries } from '@testing-library/react'
import type { UserEvent } from '@testing-library/user-event'

type QueryRoot = BoundFunctions<typeof queries> | typeof screen

/**
 * 打开自定义 Select 并点选一项。option 匹配 listbox 里 option 的 accessible name。
 */
export async function chooseSelectOption(
  user: UserEvent,
  triggerName: string,
  optionName: string | RegExp,
  root: QueryRoot = screen,
) {
  await user.click(root.getByLabelText(triggerName))
  const listbox = await screen.findByRole('listbox')
  await user.click(await within(listbox).findByRole('option', { name: optionName }))
}
