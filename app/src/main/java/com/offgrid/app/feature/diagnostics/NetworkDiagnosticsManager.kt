package com.offgrid.app.feature.diagnostics

import com.offgrid.app.core.database.AppDatabase
import com.offgrid.app.core.database.DiagnosticLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class NetworkStats(
    val activeTransport: String = "Idle",
    val isWifiConnected: Boolean = false,
    val isNearbyActive: Boolean = false,
    val directPeersCount: Int = 0,
    val activeRoutesCount: Int = 0,
    val queuedPacketsCount: Int = 0,
    val totalBytesTransferred: Long = 0L
)

class NetworkDiagnosticsManager(
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {
    private val _stats = MutableStateFlow(NetworkStats())
    val stats: StateFlow<NetworkStats> = _stats

    fun getRecentLogsFlow(): Flow<List<DiagnosticLogEntity>> {
        return database.diagnosticDao().getRecentLogsFlow()
    }

    fun log(tag: String, message: String, level: String = "INFO") {
        scope.launch(Dispatchers.IO) {
            database.diagnosticDao().logEvent(
                DiagnosticLogEntity(
                    timestamp = System.currentTimeMillis(),
                    tag = tag,
                    message = message,
                    level = level
                )
            )
            database.diagnosticDao().trimLogs()
        }
    }

    fun updateStats(transform: (NetworkStats) -> NetworkStats) {
        _stats.value = transform(_stats.value)
    }
}
