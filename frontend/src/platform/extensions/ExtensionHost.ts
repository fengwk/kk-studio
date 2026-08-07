import type {
  CommandContribution,
  DialogContribution,
  Disposable,
  InspectorContribution,
  NavigationContribution,
  OverlayContribution,
  PageContribution,
  PanelContribution,
  StatusContribution,
  TrustedReactExtension,
  WidgetContribution,
} from '@/platform/extensions/types'

type RegistryListener = () => void

interface RegisteredContribution<T> {
  contribution: T
  registrationOrder: number
}

interface ExtensionRegistration {
  contributions: Array<{ registry: ContributionRegistry<never>; dispose: Disposable }>
}

/**
 * 为一个 contribution id 保留全部候选。活动候选按优先级降序、先注册优先
 * 选取，使低优先级 contribution 能在 override 卸载时成为回退。
 */
export class ContributionRegistry<T extends { id: string; priority?: number }> {
  private readonly candidatesById = new Map<string, RegisteredContribution<T>[]>()
  private readonly listeners = new Set<RegistryListener>()
  private snapshot: readonly T[] = []

  subscribe = (listener: RegistryListener): Disposable => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = (): readonly T[] => this.snapshot

  register(contribution: T, registrationOrder: number, notify = true): Disposable {
    validateId('Contribution', contribution.id)
    const candidates = this.candidatesById.get(contribution.id) ?? []
    const registered = { contribution, registrationOrder }
    candidates.push(registered)
    this.candidatesById.set(contribution.id, candidates)
    this.refreshSnapshot()
    if (notify) {
      this.publish()
    }

    let disposed = false
    return () => {
      if (disposed) {
        return
      }
      disposed = true
      const remaining = (this.candidatesById.get(contribution.id) ?? []).filter((candidate) => candidate !== registered)
      if (remaining.length === 0) {
        this.candidatesById.delete(contribution.id)
      } else {
        this.candidatesById.set(contribution.id, remaining)
      }
      this.refreshSnapshot()
      if (notify) {
        this.publish()
      }
    }
  }

  get(id: string): T | undefined {
    return this.select(this.candidatesById.get(id))?.contribution
  }

  list(): readonly T[] {
    return this.snapshot
  }

  clear(notify = true): boolean {
    if (this.candidatesById.size === 0) {
      return false
    }
    this.candidatesById.clear()
    this.refreshSnapshot()
    if (notify) {
      this.publish()
    }
    return true
  }

  publish() {
    this.listeners.forEach((listener) => listener())
  }

  private refreshSnapshot() {
    this.snapshot = [...this.candidatesById.values()]
      .map((candidates) => this.select(candidates))
      .filter((candidate): candidate is RegisteredContribution<T> => candidate !== undefined)
      .sort((left, right) => this.compare(left, right))
      .map((candidate) => candidate.contribution)
  }

  private select(candidates: RegisteredContribution<T>[] | undefined): RegisteredContribution<T> | undefined {
    return candidates?.slice().sort((left, right) => this.compare(left, right))[0]
  }

  private compare(left: RegisteredContribution<T>, right: RegisteredContribution<T>): number {
    const priorityDifference = (right.contribution.priority ?? 0) - (left.contribution.priority ?? 0)
    return priorityDifference || left.registrationOrder - right.registrationOrder
  }
}

export class ExtensionHost {
  readonly pages = new ContributionRegistry<PageContribution>()
  readonly navigation = new ContributionRegistry<NavigationContribution>()
  readonly panels = new ContributionRegistry<PanelContribution>()
  readonly widgets = new ContributionRegistry<WidgetContribution>()
  readonly inspectors = new ContributionRegistry<InspectorContribution>()
  readonly commands = new ContributionRegistry<CommandContribution>()
  readonly statuses = new ContributionRegistry<StatusContribution>()
  readonly dialogs = new ContributionRegistry<DialogContribution>()
  readonly overlays = new ContributionRegistry<OverlayContribution>()

  private readonly listeners = new Set<RegistryListener>()
  private readonly extensionRegistrations = new Map<string, ExtensionRegistration>()
  private registrationOrder = 0
  private snapshot = { revision: 0 }

  subscribe = (listener: RegistryListener): Disposable => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = (): Readonly<{ revision: number }> => this.snapshot

  register(extension: TrustedReactExtension): Disposable {
    this.validateExtension(extension)
    if (this.extensionRegistrations.has(extension.id)) {
      throw new Error(`Extension id already registered: ${extension.id}`)
    }

    const contributions: ExtensionRegistration['contributions'] = []
    const changedRegistries = new Set<ContributionRegistry<never>>()
    const registerAll = <T extends { id: string; priority?: number }>(registry: ContributionRegistry<T>, items: T[] | undefined) => {
      for (const contribution of items ?? []) {
        const dispose = registry.register(contribution, this.registrationOrder++, false)
        contributions.push({ registry: registry as ContributionRegistry<never>, dispose })
        changedRegistries.add(registry as ContributionRegistry<never>)
      }
    }

    registerAll(this.pages, extension.pages)
    registerAll(this.navigation, extension.navigation)
    registerAll(this.panels, extension.panels)
    registerAll(this.widgets, extension.widgets)
    registerAll(this.inspectors, extension.inspectors)
    registerAll(this.commands, extension.commands)
    registerAll(this.statuses, extension.statuses)
    registerAll(this.dialogs, extension.dialogs)
    registerAll(this.overlays, extension.overlays)

    this.extensionRegistrations.set(extension.id, { contributions })
    this.publish(changedRegistries)

    let disposed = false
    return () => {
      if (disposed) {
        return
      }
      disposed = true
      this.unregister(extension.id)
    }
  }

  unregister(extensionId: string) {
    const registration = this.extensionRegistrations.get(extensionId)
    if (!registration) {
      return
    }
    this.extensionRegistrations.delete(extensionId)
    const changedRegistries = new Set<ContributionRegistry<never>>()
    for (const contribution of registration.contributions) {
      contribution.dispose()
      changedRegistries.add(contribution.registry)
    }
    this.publish(changedRegistries)
  }

  dispose() {
    if (this.extensionRegistrations.size === 0) {
      return
    }
    const changedRegistries = new Set<ContributionRegistry<never>>()
    for (const [extensionId, registration] of this.extensionRegistrations) {
      this.extensionRegistrations.delete(extensionId)
      for (const contribution of registration.contributions) {
        contribution.dispose()
        changedRegistries.add(contribution.registry)
      }
    }
    this.publish(changedRegistries)
  }

  private publish(changedRegistries: Set<ContributionRegistry<never>>) {
    changedRegistries.forEach((registry) => registry.publish())
    this.snapshot = { revision: this.snapshot.revision + 1 }
    this.listeners.forEach((listener) => listener())
  }

  private validateExtension(extension: TrustedReactExtension) {
    validateId('Extension', extension.id)
    validateContributionList('pages', extension.pages, (page) => validateNestedPath('Page', page.path))
    validateContributionList('navigation', extension.navigation, (navigation) => validateNestedPath('Navigation', navigation.path))
    validateContributionList('panels', extension.panels)
    validateContributionList('widgets', extension.widgets)
    validateContributionList('inspectors', extension.inspectors)
    validateContributionList('commands', extension.commands)
    validateContributionList('statuses', extension.statuses)
    validateContributionList('dialogs', extension.dialogs)
    validateContributionList('overlays', extension.overlays)
  }
}

function validateContributionList<T extends { id: string }>(
  registryName: string,
  contributions: T[] | undefined,
  validateContribution?: (contribution: T) => void,
) {
  const ids = new Set<string>()
  for (const contribution of contributions ?? []) {
    validateId(`Contribution in ${registryName}`, contribution.id)
    if (ids.has(contribution.id)) {
      throw new Error(`Duplicate contribution id in ${registryName}: ${contribution.id}`)
    }
    ids.add(contribution.id)
    validateContribution?.(contribution)
  }
}

function validateId(subject: string, id: string) {
  if (!id || id.trim() !== id) {
    throw new Error(`${subject} id must be non-empty`)
  }
}

function validateNestedPath(subject: string, path: string) {
  const segments = path.split('/')
  if (!path || path.trim() !== path || path.startsWith('/') || path.includes('?') || path.includes('#') || segments.some((segment) => !segment || segment === '.' || segment === '..')) {
    throw new Error(`${subject} path must be a non-empty nested relative path: ${path}`)
  }
}
