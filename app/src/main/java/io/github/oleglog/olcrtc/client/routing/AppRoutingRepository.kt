package io.github.oleglog.olcrtc.client.routing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.oleglog.olcrtc.client.data.AppRoutingEntryDao
import io.github.oleglog.olcrtc.client.data.AppRoutingEntryEntity
import io.github.oleglog.olcrtc.client.data.ClientDatabase

internal data class AppRoutingItem(
    val packageName: String,
    val label: String,
    val selected: Boolean,
    val system: Boolean,
)

internal class AppRoutingRepository(
    private val packageManager: PackageManager,
    private val entries: AppRoutingEntryDao,
) {
    fun refreshInstalled(includeSystem: Boolean): List<AppRoutingItem> {
        val stored = entries.getAll().associateBy(AppRoutingEntryEntity::packageName)
        val installed = packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .map { app ->
                val label = app.loadLabel(packageManager)?.toString()?.takeIf(String::isNotBlank)
                    ?: app.packageName
                val existing = stored[app.packageName]
                AppRoutingItem(
                    packageName = app.packageName,
                    label = label,
                    selected = existing?.selected ?: false,
                    system = app.isSystemApp,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AppRoutingItem::label).thenBy(AppRoutingItem::packageName))
            .toList()
        entries.upsert(installed.map {
            AppRoutingEntryEntity(
                packageName = it.packageName,
                selected = it.selected,
                labelSnapshot = it.label,
            )
        })
        return installed
    }

    fun selectedPackages(): Set<String> = entries.getSelectedPackages().toSet()

    fun setSelected(packageNames: Collection<String>, selected: Boolean): Int {
        val normalized = packageNames.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return 0
        return entries.setSelected(normalized, selected)
    }

    companion object {
        fun open(context: Context): AppRoutingRepository {
            val appContext = context.applicationContext
            return AppRoutingRepository(
                appContext.packageManager,
                ClientDatabase.open(appContext).appRoutingEntries(),
            )
        }
    }
}

private val ApplicationInfo.isSystemApp: Boolean
    get() = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
