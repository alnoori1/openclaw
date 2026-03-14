package ai.openclaw.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.openclaw.app.MainViewModel

private enum class CompanionTab(
  val label: String,
  val icon: ImageVector,
) {
  Home(label = "Home", icon = Icons.Default.Home),
  Sessions(label = "Sessions", icon = Icons.Default.Route),
  Chat(label = "Chat", icon = Icons.Default.ChatBubble),
  Voice(label = "Voice", icon = Icons.Default.Mic),
  Debug(label = "Debug", icon = Icons.Default.CheckCircle),
  Channels(label = "Channels", icon = Icons.Default.Inventory2),
  Admin(label = "Ops", icon = Icons.Default.AdminPanelSettings),
  Connect(label = "Connect", icon = Icons.Default.Link),
  Settings(label = "Settings", icon = Icons.Default.Settings),
}

private enum class StatusVisual {
  Connected,
  Connecting,
  Warning,
  Error,
  Offline,
}

@Composable
fun PostOnboardingTabs(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  var activeTab by rememberSaveable { mutableStateOf(CompanionTab.Home) }
  val navigationTabs = remember { CompanionTab.entries.filterNot { it == CompanionTab.Settings } }

  LaunchedEffect(activeTab) {
    viewModel.setVoiceScreenActive(activeTab == CompanionTab.Voice)
    viewModel.setDebugScreenActive(activeTab == CompanionTab.Debug)
  }

  val statusText by viewModel.statusText.collectAsState()
  val isConnected by viewModel.isConnected.collectAsState()

  val statusVisual =
    remember(statusText, isConnected) {
      val lower = statusText.lowercase()
      when {
        isConnected -> StatusVisual.Connected
        lower.contains("connecting") || lower.contains("reconnecting") -> StatusVisual.Connecting
        lower.contains("pairing") || lower.contains("approval") || lower.contains("auth") -> StatusVisual.Warning
        lower.contains("error") || lower.contains("failed") -> StatusVisual.Error
        else -> StatusVisual.Offline
      }
    }

  val density = LocalDensity.current
  val imeVisible = WindowInsets.ime.getBottom(density) > 0
  val hideTopStatusBar = activeTab == CompanionTab.Chat && imeVisible
  val hideBottomTabBar = activeTab == CompanionTab.Chat && imeVisible

  Scaffold(
    modifier = modifier,
    containerColor = Color.Transparent,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      if (!hideTopStatusBar) {
        TopStatusBar(
          statusText = statusText,
          statusVisual = statusVisual,
          showingSettings = activeTab == CompanionTab.Settings,
          onOpenSettings = {
            activeTab =
              if (activeTab == CompanionTab.Settings) {
                CompanionTab.Home
              } else {
                CompanionTab.Settings
              }
          },
        )
      }
    },
    bottomBar = {
      if (!hideBottomTabBar && activeTab != CompanionTab.Settings) {
        BottomTabBar(
          tabs = navigationTabs,
          activeTab = activeTab,
          onSelect = { activeTab = it },
        )
      }
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .consumeWindowInsets(innerPadding)
          .background(mobileBackgroundGradient),
    ) {
      when (activeTab) {
        CompanionTab.Home ->
          HomeDashboardScreen(
            viewModel = viewModel,
            onOpenSessions = { activeTab = CompanionTab.Sessions },
            onOpenChat = { activeTab = CompanionTab.Chat },
            onOpenVoice = { activeTab = CompanionTab.Voice },
            onOpenChannels = { activeTab = CompanionTab.Channels },
            onOpenAdmin = { activeTab = CompanionTab.Admin },
            onOpenConnect = { activeTab = CompanionTab.Connect },
          )
        CompanionTab.Sessions ->
          OperatorSessionsScreen(
            viewModel = viewModel,
            onOpenChat = { activeTab = CompanionTab.Chat },
          )
        CompanionTab.Chat -> ChatSheet(viewModel = viewModel)
        CompanionTab.Voice -> VoiceTabScreen(viewModel = viewModel)
        CompanionTab.Debug -> DebugTabScreen(viewModel = viewModel)
        CompanionTab.Channels ->
          OperatorChannelsScreen(
            viewModel = viewModel,
            onOpenAdmin = { activeTab = CompanionTab.Admin },
          )
        CompanionTab.Admin -> OperatorAdminScreen(viewModel = viewModel)
        CompanionTab.Connect -> ConnectTabScreen(viewModel = viewModel)
        CompanionTab.Settings -> SettingsSheet(viewModel = viewModel)
      }
    }
  }
}

@Composable
private fun TopStatusBar(
  statusText: String,
  statusVisual: StatusVisual,
  showingSettings: Boolean,
  onOpenSettings: () -> Unit,
) {
  val safeInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

  val (chipBg, chipDot, chipText, chipBorder) =
    when (statusVisual) {
      StatusVisual.Connected ->
        listOf(
          mobileSuccessSoft,
          mobileSuccess,
          mobileSuccess,
          Color(0xFFCFEBD8),
        )
      StatusVisual.Connecting ->
        listOf(
          mobileAccentSoft,
          mobileAccent,
          mobileAccent,
          Color(0xFFD5E2FA),
        )
      StatusVisual.Warning ->
        listOf(
          mobileWarningSoft,
          mobileWarning,
          mobileWarning,
          Color(0xFFEED8B8),
        )
      StatusVisual.Error ->
        listOf(
          mobileDangerSoft,
          mobileDanger,
          mobileDanger,
          Color(0xFFF3C8C8),
        )
      StatusVisual.Offline ->
        listOf(
          mobileSurface,
          mobileTextTertiary,
          mobileTextSecondary,
          mobileBorder,
        )
    }

  Surface(
    modifier = Modifier.fillMaxWidth().windowInsetsPadding(safeInsets),
    color = Color.Transparent,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = "Claw Companion",
        style = mobileTitle2,
        color = mobileText,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = chipBg,
          border = BorderStroke(1.dp, chipBorder),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              modifier = Modifier.padding(top = 1.dp),
              color = chipDot,
              shape = RoundedCornerShape(999.dp),
            ) {
              Box(modifier = Modifier.padding(4.dp))
            }
            Text(
              text = statusText.trim().ifEmpty { "Offline" },
              style = mobileCaption1,
              color = chipText,
              maxLines = 1,
            )
          }
        }
        Surface(
          onClick = onOpenSettings,
          shape = RoundedCornerShape(16.dp),
          color = if (showingSettings) mobileAccentSoft else mobileSurface.copy(alpha = 0.96f),
          border = BorderStroke(1.dp, if (showingSettings) mobileAccent.copy(alpha = 0.28f) else mobileBorder),
          shadowElevation = 0.dp,
        ) {
          Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Settings",
              tint = if (showingSettings) mobileAccent else mobileTextSecondary,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun BottomTabBar(
  tabs: List<CompanionTab>,
  activeTab: CompanionTab,
  onSelect: (CompanionTab) -> Unit,
) {
  val safeInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

  Box(modifier = Modifier.fillMaxWidth()) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = mobileSurface.copy(alpha = 0.98f),
      shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      border = BorderStroke(1.dp, mobileBorder),
      shadowElevation = 6.dp,
    ) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .windowInsetsPadding(safeInsets)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        tabs.forEach { tab ->
          val active = tab == activeTab
          Surface(
            onClick = { onSelect(tab) },
            modifier = Modifier.width(92.dp).heightIn(min = 58.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (active) mobileAccentSoft else Color.Transparent,
            border = if (active) BorderStroke(1.dp, Color(0xFFD5E2FA)) else null,
            shadowElevation = 0.dp,
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = if (active) mobileAccent else mobileTextTertiary,
              )
              Text(
                text = tab.label,
                color = if (active) mobileAccent else mobileTextSecondary,
                style = mobileCaption2.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
              )
            }
          }
        }
      }
    }
  }
}
