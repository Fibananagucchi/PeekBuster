package com.birdoffreedom.peekbuster.service

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.birdoffreedom.peekbuster.ai.AIAnalysis
import com.birdoffreedom.peekbuster.ai.AIAnalyzer
import com.birdoffreedom.peekbuster.model.AppInfo
import com.birdoffreedom.peekbuster.model.TrustScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PermissionMonitor(private val context: Context) {

    private val analyzer = AIAnalyzer()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs = context.getSharedPreferences("peekbuster_trust_data", Context.MODE_PRIVATE)

    companion object {
        val appInfoList = mutableListOf<AppInfo>()
    }

    private val suspiciousPermissions = mapOf(
        "android.permission.RECORD_AUDIO" to "Microphone",
        "android.permission.CAMERA" to "Camera",
        "android.permission.ACCESS_FINE_LOCATION" to "Precise Location",
        "android.permission.ACCESS_COARSE_LOCATION" to "Location",
        "android.permission.READ_CONTACTS" to "Contacts",
        "android.permission.READ_CALL_LOG" to "Call Log",
        "android.permission.READ_SMS" to "SMS",
        "android.permission.READ_EXTERNAL_STORAGE" to "Files & Photos"
    )

    suspend fun loadAllApps() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60 * 60 // Last 1 hour

        val usageStats = try {
            usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
        } catch (e: Exception) {
            emptyMap()
        }

        val newList = mutableListOf<AppInfo>()

        apps.forEach { appInfo ->
            if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) return@forEach

            val appName = pm.getApplicationLabel(appInfo).toString()
            val icon = try { pm.getApplicationIcon(appInfo.packageName) } catch (e: Exception) { null }
            
            val requestedPermissions = getAppPermissions(appInfo.packageName)
            val grantedCount = countGrantedPermissions(appInfo.packageName)
            
            // Real data: Check if the app was actually active recently
            val stats = usageStats[appInfo.packageName]
            val lastActivity = stats?.lastTimeUsed ?: 0L
            
            // Real data: Check for actual background ops usage
            val recentPermissionUsage = checkRecentPermissionUsage(appInfo.packageName, appInfo.uid)

            // Persist logic: Load saved status if exists, otherwise calculate
            val savedStatus = getSavedTrustScore(appInfo.packageName)
            
            // If it's a first-time load and no saved status, we mark as UNKNOWN 
            // instead of heuristic SAFE to force the user to SCAN.
            val trustScore = savedStatus ?: if (requestedPermissions.isEmpty()) TrustScore.SAFE else TrustScore.UNKNOWN

            newList.add(
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    icon = icon,
                    trustScore = trustScore,
                    permissions = requestedPermissions,
                    grantedPermissionsCount = grantedCount,
                    lastActivity = lastActivity
                )
            )
        }

        newList.sortWith(compareBy {
            when (it.trustScore) {
                TrustScore.DANGEROUS -> 0
                TrustScore.SUSPICIOUS -> 1
                TrustScore.SAFE -> 2
                TrustScore.TRUSTED -> 2
                TrustScore.UNKNOWN -> 3
            }
        })
        
        appInfoList.clear()
        appInfoList.addAll(newList)
    }

    private fun checkRecentPermissionUsage(packageName: String, uid: Int): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val ops = listOf(
            AppOpsManager.OPSTR_CAMERA,
            AppOpsManager.OPSTR_RECORD_AUDIO,
            AppOpsManager.OPSTR_FINE_LOCATION,
            AppOpsManager.OPSTR_COARSE_LOCATION
        )

        val oneHourAgo = System.currentTimeMillis() - 1000 * 60 * 60

        ops.forEach { op ->
            try {
                // Check if the op was used recently. 
                // We can use getPackagesForOps for older APIs or historical ops for newer ones.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // For modern Android, we check the historical ops if available, 
                    // or use the current note/start state
                    val mode = appOps.unsafeCheckOpNoThrow(op, uid, packageName)
                    if (mode == AppOpsManager.MODE_ALLOWED) {
                        return true
                    }
                } else {
                    val mode = appOps.checkOpNoThrow(op, uid, packageName)
                    if (mode == AppOpsManager.MODE_ALLOWED) {
                        return true
                    }
                }
            } catch (e: Exception) {}
        }
        return false
    }

    private fun getAppPermissions(packageName: String): List<String> {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            packageInfo.requestedPermissions
                ?.filter { permission -> suspiciousPermissions.containsKey(permission) }
                ?.mapNotNull { suspiciousPermissions[it] }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun countGrantedPermissions(packageName: String): Int {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            var count = 0
            packageInfo.requestedPermissions?.forEachIndexed { index, perm ->
                if (suspiciousPermissions.containsKey(perm)) {
                    val flags = packageInfo.requestedPermissionsFlags?.get(index) ?: 0
                    if ((flags and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                        count++
                    }
                }
            }
            count
        } catch (e: Exception) {
            0
        }
    }

    private fun calculateTrustScore(
        packageName: String,
        permissions: List<String>,
        hasRecentActivity: Boolean
    ): TrustScore {
        // High risk: Sensitive permissions + Active usage
        if (hasRecentActivity) {
            if (permissions.contains("Microphone") || permissions.contains("Camera") || permissions.contains("Precise Location")) {
                return TrustScore.DANGEROUS
            }
        }

        // Medium risk: Too many suspicious permissions requested
        val riskCount = permissions.size
        if (riskCount >= 4) return TrustScore.DANGEROUS
        if (riskCount >= 2) return TrustScore.SUSPICIOUS
        
        return TrustScore.SAFE
    }

    fun saveTrustScore(packageName: String, score: TrustScore) {
        prefs.edit().putString(packageName, score.name).apply()
    }

    fun clearAllPersistedData() {
        prefs.edit().clear().apply()
    }

    fun clearOnlyTrustedApps() {
        val allEntries = prefs.all
        val editor = prefs.edit()
        allEntries.forEach { (pkg, value) ->
            if (value == TrustScore.TRUSTED.name) {
                editor.remove(pkg)
            }
        }
        editor.apply()
    }

    private fun getSavedTrustScore(packageName: String): TrustScore? {
        val scoreName = prefs.getString(packageName, null) ?: return null
        return try {
            TrustScore.valueOf(scoreName)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getAppAnalysis(appInfo: AppInfo): AIAnalysis {
        return analyzer.analyzeAppFull(appInfo)
    }
}