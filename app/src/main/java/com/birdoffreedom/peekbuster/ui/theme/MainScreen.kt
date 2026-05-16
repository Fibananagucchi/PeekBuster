package com.birdoffreedom.peekbuster.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.birdoffreedom.peekbuster.ai.AIAnalysis
import com.birdoffreedom.peekbuster.data.MainViewModel
import com.birdoffreedom.peekbuster.model.AppConnection
import com.birdoffreedom.peekbuster.model.AppInfo
import com.birdoffreedom.peekbuster.model.TrustScore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
) {
    val appInfoList by viewModel.appInfoList.collectAsState()
    val connections by viewModel.connections.collectAsState()
    val isVpnRunning by viewModel.isVpnRunning.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val selectedAnalysis by viewModel.selectedAnalysis.collectAsState()
    val privacyScore by viewModel.privacyScore.collectAsState()
    val hasUsageAccess by viewModel.hasUsageStatsPermission.collectAsState()
    val monitoredPackageName by viewModel.monitoredPackageName.collectAsState()
    val isTrustedModalOpen by viewModel.isTrustedModalOpen.collectAsState()

    var currentTab by remember { mutableStateOf(TabItem.Advisor) }

    // Re-check permissions when the app resumes (e.g., user returning from settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        bottomBar = {
            CyberBottomBar(
                selectedTab = currentTab,
                onTabSelected = { currentTab = it }
            )
        },
        containerColor = CyberBlack
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Background Glow
            Box(
                modifier = Modifier
                    .size(400.dp)
                    .offset(x = (-100).dp, y = (-100).dp)
                    .blur(100.dp)
                    .background(NeonGreen.copy(alpha = 0.05f), CircleShape)
            )

            Column(modifier = Modifier.fillMaxSize()) {
                CyberTopBar(
                    isVpnRunning = isVpnRunning,
                    onToggleVpn = { viewModel.toggleVpn() }
                )

                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                    },
                    label = "TabTransition"
                ) { tab ->
                    when (tab) {
                        TabItem.Advisor -> DashboardTab(
                            privacyScore = privacyScore,
                            isVpnRunning = isVpnRunning,
                            connections = connections,
                            onAnalyze = { viewModel.analyzeApp(it) },
                            onNavigateToAudit = { currentTab = TabItem.Audit }
                        )
                        TabItem.Audit -> AuditTab(
                            appInfoList = appInfoList,
                            onAnalyze = { viewModel.analyzeApp(it) }
                        )
                        TabItem.Traffic -> TrafficTab(
                            connections = connections,
                            onAnalyze = { viewModel.analyzeConnection(it) },
                            monitoredPackageName = monitoredPackageName,
                            appInfoList = appInfoList,
                            onStopMonitoring = { viewModel.stopMonitoring() }
                        )
                    }
                }
            }

            // Overlays
            if (!hasUsageAccess) {
                PermissionOverlay(onGrant = { viewModel.openUsageAccessSettings() })
            }

            if (selectedAnalysis != null) {
                CyberAnalysisDialog(
                    appInfo = selectedAnalysis!!.first,
                    analysis = selectedAnalysis!!.second,
                    onDismiss = { viewModel.dismissAnalysis() },
                    onBlock = { viewModel.openAppSettings(selectedAnalysis!!.first) },
                    onKill = { viewModel.killApp(selectedAnalysis!!.first) },
                    onToggleTrust = { viewModel.toggleAppTrust(selectedAnalysis!!.first, it) },
                    onMonitor = { viewModel.monitorApp(selectedAnalysis!!.first.packageName) }
                )
            }

            if (isTrustedModalOpen) {
                TrustedAppsModal(
                    trustedApps = appInfoList.filter { it.trustScore == TrustScore.TRUSTED },
                    onDismiss = { viewModel.showTrustedModal(false) },
                    onRemoveTrust = { viewModel.toggleAppTrust(it, false) },
                    onRemoveAll = { viewModel.clearTrustedApps() }
                )
            }
        }
    }
}

enum class TabItem(val title: String, val icon: ImageVector) {
    Advisor("Advisor", Icons.Default.Radar),
    Audit("Audit", Icons.Default.Security),
    Traffic("Traffic", Icons.Default.Podcasts)
}

@Composable
fun CyberTopBar(isVpnRunning: Boolean, onToggleVpn: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "PEEKBUSTER",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                letterSpacing = 2.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isVpnRunning) NeonGreen else Color.Gray, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "CYBER-SENTINEL ACTIVE",
                    color = NeonGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isVpnRunning) NeonGreen.copy(alpha = 0.15f) else CyberNavyLight)
                .border(
                    1.dp,
                    if (isVpnRunning) NeonGreen else GlassStroke,
                    RoundedCornerShape(14.dp)
                )
                .clickable { onToggleVpn() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isVpnRunning) Icons.Default.Shield else Icons.Default.ShieldMoon,
                tint = if (isVpnRunning) NeonGreen else Color.Gray,
                modifier = Modifier.size(26.dp),
                contentDescription = null
            )
        }
    }
}

@Composable
fun CyberBottomBar(selectedTab: TabItem, onTabSelected: (TabItem) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        color = CyberNavyLight.copy(alpha = 0.8f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, GlassStroke)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            TabItem.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                val color by animateColorAsState(if (isSelected) NeonGreen else Color.Gray, label = "")
                
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Text(tab.title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DashboardTab(
    privacyScore: Int,
    isVpnRunning: Boolean,
    connections: List<AppConnection>,
    onAnalyze: (AppInfo) -> Unit,
    onNavigateToAudit: () -> Unit
) {
    val viewModel: MainViewModel = viewModel()
    val appInfoList by viewModel.appInfoList.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val discoveredThreats by viewModel.discoveredThreats.collectAsState()

    val suspiciousCount = appInfoList.count { it.trustScore == TrustScore.SUSPICIOUS }
    val dangerousCount = appInfoList.count { it.trustScore == TrustScore.DANGEROUS }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp, start = 24.dp, end = 24.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main Action: System Health
        item {
            ActionableHealthCard(
                privacyScore = privacyScore, 
                isVpnRunning = isVpnRunning,
                isScanning = isAnalyzing,
                progress = scanProgress,
                discoveredThreats = discoveredThreats
            ) {
                viewModel.startFullScan()
            }
        }

        // Functional Widgets Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CounterWidget(
                    label = "DANGEROUS",
                    value = "$dangerousCount",
                    icon = Icons.Default.GppBad,
                    color = AlertRed,
                    modifier = Modifier.weight(1f)
                )
                CounterWidget(
                    label = "SUSPICIOUS",
                    value = "$suspiciousCount",
                    icon = Icons.Default.Warning,
                    color = AlertOrange,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Real Action Buttons
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DashboardActionButton(
                    title = "SCAN ALL APPLICATIONS",
                    subtitle = "Comprehensive permission audit",
                    icon = Icons.Default.AppRegistration,
                    onClick = onNavigateToAudit
                )
                DashboardActionButton(
                    title = "MANAGE TRUSTED APPS",
                    subtitle = "View and edit your whitelist",
                    icon = Icons.Default.VerifiedUser,
                    onClick = { viewModel.showTrustedModal(true) }
                )
                DashboardActionButton(
                    title = "CLEAR DATA CACHE",
                    subtitle = "Reset all saved analysis history",
                    icon = Icons.Default.DeleteSweep,
                    onClick = { viewModel.clearCache() }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun CounterWidget(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier) {
    Surface(
        modifier = modifier.height(115.dp),
        color = CyberNavyLight.copy(0.4f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(GlassStroke, color.copy(alpha = 0.2f))))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.weight(1f))
            Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun DashboardActionButton(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        color = GlassWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GlassStroke)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = NeonCyan, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.Gray, fontSize = 10.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
        }
    }
}

@Composable
fun AuditTab(appInfoList: List<AppInfo>, onAnalyze: (AppInfo) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 120.dp, start = 24.dp, end = 24.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(appInfoList) { app ->
            GlassAppCard(app, onAnalyze = { onAnalyze(app) })
        }
    }
}

@Composable
fun TrafficTab(
    connections: List<AppConnection>,
    onAnalyze: (AppConnection) -> Unit,
    monitoredPackageName: String?,
    appInfoList: List<AppInfo>,
    onStopMonitoring: () -> Unit
) {
    val monitoredApp = monitoredPackageName?.let { pkg -> appInfoList.find { it.packageName == pkg } }

    if (connections.isEmpty() && monitoredPackageName == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("WAITING FOR DATA PACKETS...", color = NeonGreen.copy(alpha = 0.3f), fontSize = 12.sp)
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp, start = 24.dp, end = 24.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (monitoredPackageName != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonCyan.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("MONITORING ACTIVE", color = NeonCyan, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                Text(monitoredApp?.appName ?: monitoredPackageName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Button(
                                onClick = onStopMonitoring,
                                colors = ButtonDefaults.buttonColors(containerColor = AlertRed.copy(alpha = 0.2f)),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("STOP", color = AlertRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                if (connections.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("NO TRAFFIC DETECTED FOR THIS APP YET", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }

            items(connections) { conn ->
                GlassTrafficCard(conn, onAudit = { onAnalyze(conn) })
            }
        }
    }
}

@Composable
fun GlassAppCard(app: AppInfo, onAnalyze: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CyberNavyLight.copy(alpha = 0.5f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GlassStroke)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(trustScoreColor(app.trustScore).copy(alpha = 0.15f))
                    .border(1.dp, trustScoreColor(app.trustScore).copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(trustScoreEmoji(app.trustScore), fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${app.grantedPermissionsCount}/${app.permissions.size} PERMISSIONS", color = Color.Gray, fontSize = 10.sp)
                    if (app.trustScore == TrustScore.DANGEROUS) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("CRITICAL THREAT", color = AlertRed, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    } else if (app.trustScore == TrustScore.SUSPICIOUS) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SUSPICIOUS", color = AlertOrange, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Button(
                onClick = onAnalyze,
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen.copy(alpha = 0.1f)),
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.2f))
            ) {
                Text("SCAN", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun GlassTrafficCard(conn: AppConnection, onAudit: () -> Unit) {
    val statusColor = if (conn.isMonitored) NeonCyan else trustScoreColor(conn.trustScore)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CyberNavyLight.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (conn.isMonitored) NeonCyan.copy(alpha = 0.5f) else statusColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Public, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(conn.appName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (conn.isMonitored) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(NeonCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("MONITORED", color = NeonCyan, fontSize = 7.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
                Text(conn.domain.uppercase(), color = Color.Gray, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            TextButton(onClick = onAudit) {
                Text("AUDIT", color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun GlassConnectionCard(conn: AppConnection) {
    val statusColor = trustScoreColor(conn.trustScore)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(6.dp).background(statusColor, CircleShape))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            "${conn.appName} → ${conn.domain}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
fun TrustedAppsModal(
    trustedApps: List<AppInfo>,
    onDismiss: () -> Unit,
    onRemoveTrust: (AppInfo) -> Unit,
    onRemoveAll: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
            color = CyberNavy,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(2.dp, NeonGreen.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("TRUSTED WHITELIST", color = NeonGreen, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                if (trustedApps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("NO APPS IN WHITELIST", color = Color.Gray, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(trustedApps) { app ->
                            Surface(
                                color = CyberNavyLight,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GlassStroke)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.appName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(app.packageName, color = Color.Gray, fontSize = 9.sp)
                                    }
                                    IconButton(onClick = { onRemoveTrust(app) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = AlertRed)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (trustedApps.isNotEmpty()) {
                        Button(
                            onClick = onRemoveAll,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AlertRed.copy(alpha = 0.1f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.3f))
                        ) {
                            Text("REMOVE ALL", color = AlertRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Button(
                        onClick = onDismiss,
                        modifier = if (trustedApps.isEmpty()) Modifier.fillMaxWidth() else Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.3f))
                    ) {
                        Text("CLOSE", color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CyberAnalysisDialog(
    appInfo: AppInfo,
    analysis: AIAnalysis,
    onDismiss: () -> Unit,
    onBlock: () -> Unit,
    onKill: () -> Unit,
    onToggleTrust: (Boolean) -> Unit,
    onMonitor: () -> Unit
) {
    val appName = appInfo.appName
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .heightIn(max = 650.dp),
            color = CyberNavy,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(2.dp, NeonGreen.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Header - Fixed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).background(NeonGreen, CircleShape))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("INTEL REPORT: $appName", color = NeonGreen, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(analysis.explanation, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
                    
                    if (analysis.encryptionStatus != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = NeonGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "TRAFFIC STATUS: ${analysis.encryptionStatus}",
                                color = NeonGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(trustScoreColor(analysis.trustScore).copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Text(analysis.whyDangerous, color = trustScoreColor(analysis.trustScore), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Button 0: Monitor (New)
                        CyberActionButton(
                            text = "MONITOR TRAFFIC",
                            icon = Icons.Default.Visibility,
                            containerColor = NeonCyan.copy(alpha = 0.1f),
                            contentColor = NeonCyan,
                            borderColor = NeonCyan.copy(alpha = 0.3f),
                            onClick = onMonitor
                        )

                        // Button 1: Kill / Stop (Immediate action)
                        CyberActionButton(
                            text = "NEUTRALIZE APP PERMISSIONS",
                            icon = Icons.Default.FlashOn,
                            containerColor = AlertOrange,
                            contentColor = CyberBlack,
                            onClick = onKill
                        )

                        // Button 2: Settings / Revoke (System action)
                        CyberActionButton(
                            text = "MANAGE PERMISSIONS",
                            icon = Icons.Default.SettingsSuggest,
                            containerColor = CyberNavyLight,
                            contentColor = Color.White,
                            borderColor = GlassStroke,
                            onClick = onBlock
                        )

                        // Button 3: Trust / Stop Trusting
                        if (appInfo.trustScore == TrustScore.TRUSTED) {
                            CyberActionButton(
                                text = "STOP TRUSTING",
                                icon = Icons.Default.Cancel,
                                containerColor = AlertRed.copy(alpha = 0.1f),
                                contentColor = AlertRed,
                                borderColor = AlertRed.copy(alpha = 0.3f),
                                onClick = { onToggleTrust(false) }
                            )
                        } else {
                            CyberActionButton(
                                text = "TRUST APP",
                                icon = Icons.Default.CheckCircle,
                                containerColor = NeonGreen.copy(alpha = 0.1f),
                                contentColor = NeonGreen,
                                borderColor = NeonGreen.copy(alpha = 0.3f),
                                onClick = { onToggleTrust(true) }
                            )
                        }
                    }
                }
                
                // Footer - Fixed
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)
                ) {
                    Text("CLOSE REPORT", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CyberActionButton(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color = Color.Transparent,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun PermissionOverlay(onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack.copy(alpha = 0.95f))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SECURE ACCESS REQUIRED", color = NeonGreen, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "PeekBuster needs Usage Access to monitor process activity and sensor calls.",
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("AUTHORIZE SYSTEM SCAN", color = CyberBlack, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun LoadingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize().background(CyberBlack.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = NeonGreen, strokeWidth = 4.dp)
    }
}

fun trustScoreColor(score: TrustScore): Color = when (score) {
    TrustScore.SAFE, TrustScore.TRUSTED -> NeonGreen
    TrustScore.SUSPICIOUS -> AlertOrange
    TrustScore.DANGEROUS -> AlertRed
    TrustScore.UNKNOWN -> Color.Gray
}

fun trustScoreEmoji(score: TrustScore): String = when (score) {
    TrustScore.SAFE, TrustScore.TRUSTED -> "🟢"
    TrustScore.SUSPICIOUS -> "🟡"
    TrustScore.DANGEROUS -> "🔴"
    TrustScore.UNKNOWN -> "⚪"
}

@Composable
fun ActionableHealthCard(
    privacyScore: Int, 
    isVpnRunning: Boolean, 
    isScanning: Boolean,
    progress: Float,
    discoveredThreats: List<AppInfo>,
    onScan: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        color = CyberNavyLight.copy(alpha = 0.6f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Brush.horizontalGradient(
            if (isScanning) listOf(NeonCyan, NeonGreen)
            else listOf(NeonGreen.copy(0.4f), NeonCyan.copy(0.4f))
        ))
    ) {
        Box(modifier = Modifier.padding(24.dp)) {
            Column {
                Text(
                    "SYSTEM SECURITY OVERVIEW",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${discoveredThreats.size}",
                        color = if (discoveredThreats.isEmpty()) NeonGreen else AlertRed,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.height(72.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = "POSSIBLE ACTIVE THREATS",
                            color = if (discoveredThreats.isEmpty()) Color.Gray else AlertRed,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (discoveredThreats.isEmpty()) "SYSTEM SECURE" else "ATTENTION",
                            color = if (discoveredThreats.isEmpty()) NeonGreen.copy(0.6f) else AlertRed.copy(0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (discoveredThreats.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "THREAT LOG [RECENT ACTIVITY]:",
                        color = Color.White.copy(0.3f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(discoveredThreats.reversed()) { threat ->
                            Surface(
                                color = if (threat.trustScore == TrustScore.DANGEROUS) AlertRed.copy(0.15f) else AlertOrange.copy(0.15f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (threat.trustScore == TrustScore.DANGEROUS) AlertRed.copy(0.4f) else AlertOrange.copy(0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(6.dp).background(if (threat.trustScore == TrustScore.DANGEROUS) AlertRed else AlertOrange, CircleShape))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        threat.appName.uppercase(),
                                        color = if (threat.trustScore == TrustScore.DANGEROUS) AlertRed else AlertOrange,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        val infiniteTransition = rememberInfiniteTransition(label = "")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ), label = ""
                        )

                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isVpnRunning) NeonGreen else Color.Gray, 
                                    CircleShape
                                )
                                .border(1.dp, Color.White.copy(0.2f), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isVpnRunning) "SHIELD ACTIVE" else "SHIELD OFFLINE",
                            color = if (isVpnRunning) NeonGreen else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                    
                    Button(
                        onClick = onScan,
                        enabled = !isScanning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isScanning) CyberNavy else NeonGreen,
                            contentColor = CyberBlack,
                            disabledContainerColor = CyberNavy,
                            disabledContentColor = NeonCyan
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(44.dp).widthIn(min = 140.dp)
                    ) {
                        if (isScanning) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = NeonCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "${(progress * 100).toInt()}%", 
                                    fontWeight = FontWeight.Black, 
                                    fontSize = 12.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        } else {
                            Text(
                                "SCAN SYSTEM", 
                                fontWeight = FontWeight.Black, 
                                fontSize = 12.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
