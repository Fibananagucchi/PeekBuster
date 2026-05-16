package com.birdoffreedom.peekbuster.model

data class AppConnection(
    val id: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val domain: String,
    val ipAddress: String = "",
    val port: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false,
    val trustScore: TrustScore = TrustScore.UNKNOWN,
    val aiExplanation: String? = null,
    val isMonitored: Boolean = false
)

enum class TrustScore {
    SAFE,
    SUSPICIOUS,
    DANGEROUS,
    UNKNOWN,
    TRUSTED
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable?,
    val trustScore: TrustScore = TrustScore.UNKNOWN,
    val totalConnections: Int = 0,
    val blockedConnections: Int = 0,
    val permissions: List<String> = emptyList(),
    val grantedPermissionsCount: Int = 0,
    val lastActivity: Long = 0L,
    val isUserTrusted: Boolean = false,
    val isMonitored: Boolean = false
)

data class PermissionEvent(
    val id: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val permission: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuspicious: Boolean = false
)