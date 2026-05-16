package com.birdoffreedom.peekbuster.data

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.birdoffreedom.peekbuster.ai.AIAnalysis
import com.birdoffreedom.peekbuster.ai.AIAnalyzer
import com.birdoffreedom.peekbuster.model.AppConnection
import com.birdoffreedom.peekbuster.model.AppInfo
import com.birdoffreedom.peekbuster.model.TrustScore
import com.birdoffreedom.peekbuster.service.PermissionMonitor
import com.birdoffreedom.peekbuster.service.PeekBusterVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val permissionMonitor = PermissionMonitor(application)
    private val analyzer = AIAnalyzer()

    // UI state
    private val _appInfoList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appInfoList: StateFlow<List<AppInfo>> = _appInfoList

    private val _connections = MutableStateFlow<List<AppConnection>>(emptyList())
    val connections: StateFlow<List<AppConnection>> = _connections

    private val _isVpnRunning = MutableStateFlow(false)
    val isVpnRunning: StateFlow<Boolean> = _isVpnRunning

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress

    private val _discoveredThreats = MutableStateFlow<List<AppInfo>>(emptyList())
    val discoveredThreats: StateFlow<List<AppInfo>> = _discoveredThreats

    private val _lastScanTime = MutableStateFlow<Long>(0)
    val lastScanTime: StateFlow<Long> = _lastScanTime

    private val _selectedAnalysis = MutableStateFlow<Pair<AppInfo, AIAnalysis>?>(null)
    val selectedAnalysis: StateFlow<Pair<AppInfo, AIAnalysis>?> = _selectedAnalysis

    private val _isTrustedModalOpen = MutableStateFlow(false)
    val isTrustedModalOpen: StateFlow<Boolean> = _isTrustedModalOpen

    private val _monitoredPackageName = MutableStateFlow<String?>(null)
    val monitoredPackageName: StateFlow<String?> = _monitoredPackageName

    private val _privacyScore = MutableStateFlow(100)
    val privacyScore: StateFlow<Int> = _privacyScore

    private val _hasUsageStatsPermission = MutableStateFlow(false)
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission

    init {
        viewModelScope.launch {
            checkPermissions()
            permissionMonitor.loadAllApps()
        }

        // Sync UI
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val apps = PermissionMonitor.appInfoList.toList().map { 
                    if (it.packageName == _monitoredPackageName.value) it.copy(isMonitored = true) else it.copy(isMonitored = false)
                }.sortedWith(compareBy {
                    when (it.trustScore) {
                        TrustScore.DANGEROUS -> 0
                        TrustScore.SUSPICIOUS -> 1
                        TrustScore.SAFE -> 2
                        TrustScore.TRUSTED -> 2
                        TrustScore.UNKNOWN -> 3
                    }
                })
                // Sort consistently
                val sortedApps = apps.sortedWith(compareBy {
                    when (it.trustScore) {
                        TrustScore.DANGEROUS -> 0
                        TrustScore.SUSPICIOUS -> 1
                        TrustScore.SAFE -> 2
                        TrustScore.TRUSTED -> 2
                        TrustScore.UNKNOWN -> 3
                    }
                })
                _appInfoList.value = sortedApps
                
                // Refresh trust scores in connections based on latest Audit data
                val currentConnections = PeekBusterVpnService.connections.toList()
                val monitoredPkg = _monitoredPackageName.value
                
                val filteredConnections = if (monitoredPkg != null) {
                    currentConnections.filter { it.packageName == monitoredPkg }
                } else {
                    currentConnections
                }

                val updatedConnections = filteredConnections.map { conn ->
                    val auditApp = apps.find { it.packageName == conn.packageName }
                    val auditScore = auditApp?.trustScore ?: TrustScore.UNKNOWN
                    val isMonitored = auditApp?.isMonitored ?: false
                    
                    // Priority 1: Use specific connection analysis if available
                    // Priority 2: Fallback to the general Audit score for that app
                    val finalScore = if (conn.aiExplanation != null) conn.trustScore else auditScore
                    
                    if (conn.trustScore != finalScore || conn.isMonitored != isMonitored) {
                        conn.copy(trustScore = finalScore, isMonitored = isMonitored)
                    } else {
                        conn
                    }
                }
                
                _connections.value = updatedConnections
                _isVpnRunning.value = PeekBusterVpnService.isRunning
                
                // Continuous threat tracking
                val threats = apps.filter { it.trustScore == TrustScore.DANGEROUS || it.trustScore == TrustScore.SUSPICIOUS }
                _discoveredThreats.value = threats

                calculatePrivacyScore(apps)
            }
        }
    }

    private fun calculatePrivacyScore(apps: List<AppInfo>) {
        if (apps.isEmpty()) return
        val dangerous = apps.count { it.trustScore == TrustScore.DANGEROUS }
        val suspicious = apps.count { it.trustScore == TrustScore.SUSPICIOUS }
        val score = 100 - (dangerous * 15) - (suspicious * 5)
        _privacyScore.value = score.coerceIn(0, 100)
    }

    fun analyzeApp(appInfo: AppInfo) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            val analysis = permissionMonitor.getAppAnalysis(appInfo)
            
            // Update the app info in the global list with AI findings
            val currentApps = PermissionMonitor.appInfoList
            val index = currentApps.indexOfFirst { it.packageName == appInfo.packageName }
            if (index != -1) {
                val currentApp = currentApps[index]
                // We ALWAYS update if the AI says it's SAFE or if we are upgrading a status.
                // We only block "flip-flopping" if it's already user-trusted.
                if (currentApp.trustScore != TrustScore.TRUSTED) {
                    val newScore = analysis.trustScore
                    currentApps[index] = currentApp.copy(trustScore = newScore)
                    permissionMonitor.saveTrustScore(appInfo.packageName, newScore)
                }
            }
            
            // Immediately sync the state flow so the dialog and background show the same thing
            val updatedApp = currentApps.find { it.packageName == appInfo.packageName } ?: appInfo
            _selectedAnalysis.value = Pair(updatedApp, analysis)
            
            // Trigger a quick UI refresh
            _appInfoList.value = currentApps.toList().sortedWith(compareBy {
                when (it.trustScore) {
                    TrustScore.DANGEROUS -> 0
                    TrustScore.SUSPICIOUS -> 1
                    TrustScore.SAFE -> 2
                    TrustScore.TRUSTED -> 2
                    TrustScore.UNKNOWN -> 3
                }
            })
            
            _isAnalyzing.value = false
        }
    }

    fun analyzeConnection(connection: AppConnection) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            val analysis = analyzer.analyzeConnectionFull(connection)
            
            // Update the connection in the global list
            val currentConns = PeekBusterVpnService.connections
            val index = currentConns.indexOfFirst { it.id == connection.id }
            if (index != -1) {
                val updatedConn = currentConns[index].copy(
                    trustScore = analysis.trustScore,
                    aiExplanation = analysis.explanation
                )
                currentConns[index] = updatedConn
            }

            val appInfo = _appInfoList.value.find { it.packageName == connection.packageName } 
                ?: AppInfo(connection.packageName, connection.appName, null)
            _selectedAnalysis.value = Pair(appInfo, analysis)
            _isAnalyzing.value = false
        }
    }

    fun toggleAppTrust(appInfo: AppInfo, trust: Boolean) {
        val currentApps = PermissionMonitor.appInfoList
        val index = currentApps.indexOfFirst { it.packageName == appInfo.packageName }
        if (index != -1) {
            val newScore = if (trust) TrustScore.TRUSTED else TrustScore.UNKNOWN
            val updatedApp = currentApps[index].copy(trustScore = newScore)
            currentApps[index] = updatedApp
            permissionMonitor.saveTrustScore(appInfo.packageName, newScore)
            _selectedAnalysis.value = null
        }
    }

    fun startFullScan() {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _scanProgress.value = 0f
            _discoveredThreats.value = emptyList()
            
            // Phase 1: Refresh raw application data (Now properly awaited)
            permissionMonitor.loadAllApps()
            
            // Phase 2: Reset scores to UNKNOWN (White) at start of scan
            // This provides visual feedback that a fresh analysis is happening.
            val initialApps = PermissionMonitor.appInfoList.map { 
                if (it.trustScore != TrustScore.TRUSTED) it.copy(trustScore = TrustScore.UNKNOWN) else it 
            }
            PermissionMonitor.appInfoList.clear()
            PermissionMonitor.appInfoList.addAll(initialApps)
            _appInfoList.value = initialApps
            
            // Phase 3: Sequential High-Precision AI Analysis
            if (initialApps.isNotEmpty()) {
                val totalApps = initialApps.size
                initialApps.forEachIndexed { index, app ->
                    // WE ANALYZE ALL APPS sequentially for maximum precision
                    if (app.trustScore != TrustScore.TRUSTED) {
                        // Longer delay to prevent API congestion and ensure stability
                        delay(1200)
                        
                        // Perform the same analysis used in the manual button
                        val analysis = permissionMonitor.getAppAnalysis(app)
                        
                        val currentApps = PermissionMonitor.appInfoList
                        val appIdx = currentApps.indexOfFirst { it.packageName == app.packageName }
                        if (appIdx != -1) {
                            val currentApp = currentApps[appIdx]
                            // ONLY update if AI provided a definitive score
                            if (analysis.trustScore != TrustScore.UNKNOWN) {
                                if (currentApp.trustScore != TrustScore.TRUSTED) {
                                    val newScore = analysis.trustScore
                                    currentApps[appIdx] = currentApp.copy(trustScore = newScore)
                                    permissionMonitor.saveTrustScore(app.packageName, newScore)
                                }
                            }
                        }
                    }
                    
                    // Update progress incrementally
                    val progress = (index + 1).toFloat() / totalApps.toFloat()
                    _scanProgress.value = progress
                    
                    // Immediate UI sync to show results as they come in
                    val currentList = PermissionMonitor.appInfoList.toList().sortedWith(compareBy {
                        when (it.trustScore) {
                            TrustScore.DANGEROUS -> 0
                            TrustScore.SUSPICIOUS -> 1
                            TrustScore.SAFE -> 2
                            TrustScore.TRUSTED -> 2
                            TrustScore.UNKNOWN -> 3
                        }
                    })
                    _appInfoList.value = currentList
                    _discoveredThreats.value = currentList.filter { 
                        it.trustScore == TrustScore.DANGEROUS || it.trustScore == TrustScore.SUSPICIOUS 
                    }
                    calculatePrivacyScore(currentList)
                }
            }
            
            _lastScanTime.value = System.currentTimeMillis()
            _isAnalyzing.value = false
        }
    }

    fun clearCache() {
        // 1. Clear AI Analysis cache
        AIAnalyzer.clearCache()
        
        // 2. Clear Traffic logs (VPN connections list)
        PeekBusterVpnService.connections.clear()
        
        // 3. Reset UI states for traffic and analysis
        _connections.value = emptyList()
        _selectedAnalysis.value = null
        
        // Note: Persisted Trust/Dangerous scores in PermissionMonitor are NOT cleared here anymore.
        // Also we don't reload raw app list to avoid flickering in Audit.
    }

    fun clearTrustedApps() {
        // Only clear the status of apps the user manually trusted
        permissionMonitor.clearOnlyTrustedApps()
        
        // Refresh the app list to show the original Audit scores
        viewModelScope.launch {
            permissionMonitor.loadAllApps()
        }
        _selectedAnalysis.value = null
    }

    fun dismissAnalysis() {
        _selectedAnalysis.value = null
    }

    fun showTrustedModal(show: Boolean) {
        _isTrustedModalOpen.value = show
    }

    fun monitorApp(packageName: String) {
        _monitoredPackageName.value = packageName
        dismissAnalysis()
    }

    fun stopMonitoring() {
        _monitoredPackageName.value = null
    }

    fun checkPermissions() {
        val appOps = getApplication<Application>().getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getApplication<Application>().packageName)
        } else {
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getApplication<Application>().packageName)
        }
        val isGranted = mode == android.app.AppOpsManager.MODE_ALLOWED
        
        if (isGranted != _hasUsageStatsPermission.value) {
            _hasUsageStatsPermission.value = isGranted
            if (isGranted) {
                // Refresh apps immediately once permission is granted
                viewModelScope.launch {
                    permissionMonitor.loadAllApps()
                }
            }
        }
    }

    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        getApplication<Application>().startActivity(intent)
    }

    fun toggleVpn() {
        val action = if (_isVpnRunning.value) PeekBusterVpnService.ACTION_STOP else PeekBusterVpnService.ACTION_START
        val intent = Intent(getApplication(), PeekBusterVpnService::class.java).apply {
            this.action = action
        }
        getApplication<Application>().startService(intent)
    }

    fun killApp(appInfo: AppInfo) {
        val pkg = appInfo.packageName
        val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(pkg)
        
        // For a "Force Stop" (stronger restriction), we must guide to settings
        openAppSettings(appInfo)
    }

    fun openAppSettings(appInfo: AppInfo) {
        val pkg = appInfo.packageName
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        getApplication<Application>().startActivity(intent)
        dismissAnalysis()
    }

    private fun findPackageName(appName: String): String? {
        return _appInfoList.value.find { it.appName == appName }?.packageName 
            ?: _connections.value.find { it.appName == appName }?.packageName
    }
}