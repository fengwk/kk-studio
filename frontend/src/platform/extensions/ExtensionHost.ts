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

interface RegisteredContribution<T> {
  extensionId: string
  contribution: T
  registrationOrder: number
}

/**
 * Keeps all candidates for a contribution id. The active candidate is selected
 * by descending priority and then first registration, allowing a lower-priority
 * contribution to become the fallback when an override is unloaded.
 */
export class ContributionRegistry<T extends { id: string; priority?: number }> {
  private readonly candidatesById = new Map<string, RegisteredContribution<T>[]>()

  register(extensionId: string, contribution: T, registrationOrder: number): Disposable {
    const candidates = this.candidatesById.get(contribution.id) ?? []
    const registered = { extensionId, contribution, registrationOrder }
    candidates.push(registered)
    this.candidatesById.set(contribution.id, candidates)

    return () => {
      const remaining = (this.candidatesById.get(contribution.id) ?? []).filter((candidate) => candidate !== registered)
      if (remaining.length === 0) {
        this.candidatesById.delete(contribution.id)
      } else {
        this.candidatesById.set(contribution.id, remaining)
      }
    }
  }

  get(id: string): T | undefined {
    return this.select(this.candidatesById.get(id))?.contribution
  }

  list(): T[] {
    return [...this.candidatesById.values()]
      .map((candidates) => this.select(candidates))
      .filter((candidate): candidate is RegisteredContribution<T> => candidate !== undefined)
      .sort((left, right) => this.compare(left, right))
      .map((candidate) => candidate.contribution)
  }

  clear() {
    this.candidatesById.clear()
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

  private readonly extensionDisposers = new Map<string, Disposable>()
  private registrationOrder = 0

  register(extension: TrustedReactExtension): Disposable {
    if (this.extensionDisposers.has(extension.id)) {
      throw new Error(`Extension id already registered: ${extension.id}`)
    }

    const disposers: Disposable[] = []
    const registerAll = <T extends { id: string; priority?: number }>(
      registry: ContributionRegistry<T>,
      contributions: T[] | undefined,
    ) => {
      for (const contribution of contributions ?? []) {
        disposers.push(registry.register(extension.id, contribution, this.registrationOrder++))
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

    const dispose = () => {
      if (this.extensionDisposers.delete(extension.id)) {
        disposers.reverse().forEach((disposeContribution) => disposeContribution())
      }
    }
    this.extensionDisposers.set(extension.id, dispose)
    return dispose
  }

  unregister(extensionId: string) {
    this.extensionDisposers.get(extensionId)?.()
  }

  dispose() {
    ;[...this.extensionDisposers.values()].forEach((dispose) => dispose())
  }
}
