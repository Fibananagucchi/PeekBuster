package com.birdoffreedom.peekbuster.service

import android.content.Context
import com.birdoffreedom.peekbuster.ai.AIAnalyzer
import com.birdoffreedom.peekbuster.model.AppConnection
import com.birdoffreedom.peekbuster.model.TrustScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NetworkMonitor(private val context: Context) {
    private val analyzer = AIAnalyzer()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun processConnection(packageName: String, domain: String, port: Int) {
        val pm = context.packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        // Sync TrustScore from PermissionMonitor (Audit data)
        val auditTrustScore = PermissionMonitor.appInfoList.find { it.packageName == packageName }?.trustScore ?: TrustScore.UNKNOWN

        val connection = AppConnection(
            appName = appName,
            packageName = packageName,
            domain = domain,
            port = port,
            trustScore = auditTrustScore
        )

        // For now, we just add it to the list.
        PeekBusterVpnService.connections.add(0, connection)
        
        // AUTO-ANALYZE: Automatically trigger AI analysis for new connections
        // to keep the traffic log intelligent and up-to-date.
        // We use a small delay and a background scope to avoid blocking.
        scope.launch {
            delay(1000) 
            try {
                val analysis = analyzer.analyzeConnectionFull(connection)
                if (analysis.trustScore != TrustScore.UNKNOWN) {
                    val currentConns = PeekBusterVpnService.connections
                    val index = currentConns.indexOfFirst { it.id == connection.id }
                    if (index != -1) {
                        currentConns[index] = currentConns[index].copy(
                            trustScore = analysis.trustScore,
                            aiExplanation = analysis.explanation
                        )
                    }
                }
            } catch (e: Exception) {}
        }

        if (PeekBusterVpnService.connections.size > 100) {
            PeekBusterVpnService.connections.removeAt(100)
        }
    }
}