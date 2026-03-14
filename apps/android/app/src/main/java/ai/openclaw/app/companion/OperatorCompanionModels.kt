package ai.openclaw.app.companion

import kotlinx.serialization.Serializable

@Serializable
data class ActionTemplate(
  val id: String,
  val title: String,
  val description: String,
  val method: String,
  val paramsJson: String,
  val category: String,
  val builtIn: Boolean = false,
)

data class GatewayCapabilitySnapshot(
  val serverVersion: String? = null,
  val authRole: String? = null,
  val grantedScopes: Set<String> = emptySet(),
  val supportedMethods: Set<String> = emptySet(),
  val supportedEvents: Set<String> = emptySet(),
) {
  fun supports(method: String): Boolean = supportedMethods.contains(method)

  fun hasScope(scope: String): Boolean = "operator.admin" in grantedScopes || scope in grantedScopes

  val canManageSessions: Boolean
    get() = supports("sessions.list") && hasScope("operator.read")

  val canModifySessions: Boolean
    get() = supports("sessions.reset") && hasScope("operator.admin")

  val canManageAgents: Boolean
    get() = supports("agents.list") && hasScope("operator.read")

  val canModifyAgents: Boolean
    get() = supports("agents.create") && hasScope("operator.admin")

  val canInspectChannels: Boolean
    get() = supports("channels.status") && hasScope("operator.read")

  val canReadConfig: Boolean
    get() = supports("config.get") && hasScope("operator.read")

  val canPatchConfig: Boolean
    get() = supports("config.patch") && hasScope("operator.admin")

  val canManagePairing: Boolean
    get() = supports("device.pair.list") && hasScope("operator.pairing")

  val canInspectApprovals: Boolean
    get() = supports("exec.approvals.get") && hasScope("operator.approvals")

  val canReadLogs: Boolean
    get() = supports("logs.tail") && hasScope("operator.read")

  val canRunUpdates: Boolean
    get() = supports("update.run") && hasScope("operator.admin")
}

data class OperatorSessionItem(
  val key: String,
  val title: String,
  val displayName: String?,
  val agentId: String?,
  val label: String?,
  val model: String?,
  val updatedAtMs: Long?,
  val lastMessagePreview: String?,
)

data class OperatorAgentItem(
  val id: String,
  val name: String,
  val workspace: String?,
  val summary: String?,
  val isDefault: Boolean,
)

data class ChannelAccountItem(
  val accountId: String,
  val name: String?,
  val enabled: Boolean?,
  val configured: Boolean?,
  val linked: Boolean?,
  val running: Boolean?,
  val connected: Boolean?,
  val activeRuns: Int?,
  val lastError: String?,
)

data class ChannelStatusItem(
  val id: String,
  val label: String,
  val detailLabel: String?,
  val accounts: List<ChannelAccountItem>,
) {
  val connectedAccounts: Int
    get() = accounts.count { it.connected == true || it.running == true }

  val warningCount: Int
    get() = accounts.count { !it.lastError.isNullOrBlank() || it.enabled == false || it.linked == false }
}

data class PendingDevicePairItem(
  val requestId: String,
  val deviceId: String,
  val displayName: String?,
  val role: String?,
  val scopes: List<String>,
  val remoteIp: String?,
  val requestedAtMs: Long?,
  val isRepair: Boolean,
)

data class PairedDeviceItem(
  val deviceId: String,
  val displayName: String?,
  val roles: List<String>,
  val scopes: List<String>,
  val remoteIp: String?,
  val approvedAtMs: Long?,
  val tokenSummary: String?,
)

data class NodeItem(
  val id: String,
  val displayName: String?,
  val platform: String?,
  val status: String?,
  val lastSeenAtMs: Long?,
)

data class ExecApprovalsSummary(
  val path: String,
  val exists: Boolean,
  val hash: String,
  val agentCount: Int,
  val allowlistCount: Int,
)

data class LogTailSnapshot(
  val file: String?,
  val cursor: Long?,
  val lines: List<String>,
)

data class RpcHistoryEntry(
  val id: String,
  val method: String,
  val paramsJson: String,
  val responseJson: String?,
  val errorText: String?,
  val succeeded: Boolean,
  val createdAtMs: Long,
)

data class ChannelConfigSnapshot(
  val channelId: String = "",
  val currentConfigJson: String? = null,
  val baseHash: String? = null,
  val hasExistingConfig: Boolean = false,
  val containsRedactedSecrets: Boolean = false,
  val isLoading: Boolean = false,
  val lastLoadedAtMs: Long? = null,
  val errorText: String? = null,
)

data class OperatorCompanionState(
  val isRefreshing: Boolean = false,
  val lastUpdatedMs: Long? = null,
  val capability: GatewayCapabilitySnapshot = GatewayCapabilitySnapshot(),
  val sessions: List<OperatorSessionItem> = emptyList(),
  val agents: List<OperatorAgentItem> = emptyList(),
  val channels: List<ChannelStatusItem> = emptyList(),
  val pendingPairs: List<PendingDevicePairItem> = emptyList(),
  val pairedDevices: List<PairedDeviceItem> = emptyList(),
  val nodes: List<NodeItem> = emptyList(),
  val approvals: ExecApprovalsSummary? = null,
  val logs: LogTailSnapshot = LogTailSnapshot(file = null, cursor = null, lines = emptyList()),
  val channelConfig: ChannelConfigSnapshot = ChannelConfigSnapshot(),
  val snippets: List<ActionTemplate> = emptyList(),
  val history: List<RpcHistoryEntry> = emptyList(),
  val latestNotice: String? = null,
  val sessionsError: String? = null,
  val agentsError: String? = null,
  val channelsError: String? = null,
  val pairingError: String? = null,
  val approvalsError: String? = null,
  val logsError: String? = null,
  val nodesError: String? = null,
)
