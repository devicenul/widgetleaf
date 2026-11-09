package io.devnullapps.widgetleaf.domain.usecase

import io.devnullapps.widgetleaf.domain.repository.WidgetDeckRepository
import io.devnullapps.widgetleaf.platform.WidgetHostGateway

class ReconcileOrphanWidgetsUseCase(
    private val repository: WidgetDeckRepository,
    private val widgetHostGateway: WidgetHostGateway
) {

    suspend operator fun invoke() {
        val (_, widgets) = repository.getSnapshot()
        val allocatedIds = widgetHostGateway.getAllocatedAppWidgetIds().toSet()
        val persistedIds = widgets.map { it.appWidgetId }.toSet()

        // 1. Reconcile persisted widgets
        for (widget in widgets) {
            val isAllocated = widget.appWidgetId in allocatedIds
            val isInstalled = widgetHostGateway.isProviderInstalled(
                providerPackage = widget.providerPackage,
                providerClass = widget.providerClass
            )

            if (!isAllocated || !isInstalled) {
                repository.removeWidget(widget.appWidgetId)
                if (isAllocated) {
                    widgetHostGateway.deleteAppWidgetId(widget.appWidgetId)
                }
            }
        }

        // 2. Reconcile orphan host-allocated IDs
        for (hostId in allocatedIds) {
            if (hostId !in persistedIds) {
                widgetHostGateway.deleteAppWidgetId(hostId)
            }
        }

        // 3. Prune empty pages and reindex
        repository.pruneEmptyPagesAndReindex()
    }
}
