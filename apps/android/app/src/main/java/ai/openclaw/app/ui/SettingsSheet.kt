package ai.openclaw.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.openclaw.app.AppThemeMode
import ai.openclaw.app.BuildConfig
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.VoiceInputMode
import ai.openclaw.app.voice.AssistantVoiceOption

@Composable
fun SettingsSheet(viewModel: MainViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val instanceId by viewModel.instanceId.collectAsState()
  val appThemeMode by viewModel.appThemeMode.collectAsState()
  val assistantAvatarUri by viewModel.assistantAvatarUri.collectAsState()
  val assistantVoiceSelection by viewModel.assistantVoiceSelection.collectAsState()
  val assistantVoiceOptions by viewModel.assistantVoiceOptions.collectAsState()
  val cameraEnabled by viewModel.cameraEnabled.collectAsState()
  val preventSleep by viewModel.preventSleep.collectAsState()
  val voiceInputMode by viewModel.voiceInputMode.collectAsState()

  val listState = rememberLazyListState()
  val appVersion =
    remember {
      val versionName = BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" }
      if (BuildConfig.DEBUG && !versionName.contains("dev", ignoreCase = true)) {
        "$versionName-dev"
      } else {
        versionName
      }
    }
  val listItemColors =
    ListItemDefaults.colors(
      containerColor = Color.Transparent,
      headlineColor = mobileText,
      supportingColor = mobileTextSecondary,
      trailingIconColor = mobileTextSecondary,
      leadingIconColor = mobileTextSecondary,
    )

  val photosPermission =
    if (Build.VERSION.SDK_INT >= 33) {
      Manifest.permission.READ_MEDIA_IMAGES
    } else {
      Manifest.permission.READ_EXTERNAL_STORAGE
    }

  var micPermissionGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }
  var cameraPermissionGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
  var photosPermissionGranted by remember { mutableStateOf(context.hasPermission(photosPermission)) }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      micPermissionGranted = granted
    }
  val cameraPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      cameraPermissionGranted = granted
      viewModel.setCameraEnabled(granted)
    }
  val photosPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      photosPermissionGranted = granted
    }
  val avatarPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      } catch (_: SecurityException) {
        // Ignore persisted-grant failures for transient pickers.
      }
      viewModel.setAssistantAvatarUri(uri.toString())
    }

  LaunchedEffect(Unit) {
    viewModel.refreshAssistantVoiceOptions()
  }

  DisposableEffect(lifecycleOwner, context, photosPermission) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          micPermissionGranted = context.hasPermission(Manifest.permission.RECORD_AUDIO)
          cameraPermissionGranted = context.hasPermission(Manifest.permission.CAMERA)
          photosPermissionGranted = context.hasPermission(photosPermission)
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun setCameraEnabledChecked(checked: Boolean) {
    if (!checked) {
      viewModel.setCameraEnabled(false)
      return
    }
    if (cameraPermissionGranted) {
      viewModel.setCameraEnabled(true)
    } else {
      cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(mobileBackgroundGradient),
  ) {
    LazyColumn(
      state = listState,
      modifier =
        Modifier
          .fillMaxWidth()
          .fillMaxHeight()
          .imePadding()
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            "SETTINGS",
            style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = mobileAccent,
          )
          Text("Claw Companion", style = mobileTitle2, color = mobileText)
          Text(
            "Companion permissions are limited to mic, camera, and gallery when you choose to use them.",
            style = mobileCallout,
            color = mobileTextSecondary,
          )
        }
      }
      item { HorizontalDivider(color = mobileBorder) }

      item { SettingsSectionTitle("APPEARANCE") }
      item {
        Column(modifier = Modifier.settingsRowModifier().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            AssistantAvatar(
              avatarUri = assistantAvatarUri,
              size = 60.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Assistant portrait", style = mobileHeadline, color = mobileText)
              Text(
                "Used in chat and voice. Leave empty to use the default companion mark.",
                style = mobileCallout,
                color = mobileTextSecondary,
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
              onClick = { avatarPickerLauncher.launch(arrayOf("image/*")) },
              colors = settingsPrimaryButtonColors(),
              shape = RoundedCornerShape(14.dp),
            ) {
              Text("Choose image", style = mobileCallout.copy(fontWeight = FontWeight.Bold))
            }
            Button(
              onClick = { viewModel.setAssistantAvatarUri(null) },
              enabled = assistantAvatarUri.isNotBlank(),
              colors = settingsSecondaryButtonColors(),
              shape = RoundedCornerShape(14.dp),
            ) {
              Text("Clear", style = mobileCallout.copy(fontWeight = FontWeight.Bold))
            }
          }
        }
      }
      item {
        Column(modifier = Modifier.settingsRowModifier(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
          Text(
            text = "Theme",
            style = mobileHeadline,
            color = mobileText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
          )
          ThemeModeChoice(
            title = "System",
            description = "Follow the phone theme.",
            selected = appThemeMode == AppThemeMode.System,
            onClick = { viewModel.setAppThemeMode(AppThemeMode.System) },
          )
          HorizontalDivider(color = mobileBorder)
          ThemeModeChoice(
            title = "Light",
            description = "Bright control room layout.",
            selected = appThemeMode == AppThemeMode.Light,
            onClick = { viewModel.setAppThemeMode(AppThemeMode.Light) },
          )
          HorizontalDivider(color = mobileBorder)
          ThemeModeChoice(
            title = "Dark",
            description = "Dark graphite layout for chat and ops.",
            selected = appThemeMode == AppThemeMode.Dark,
            onClick = { viewModel.setAppThemeMode(AppThemeMode.Dark) },
          )
        }
      }

      item { HorizontalDivider(color = mobileBorder) }
      item { SettingsSectionTitle("VOICE") }
      item {
        Column(modifier = Modifier.settingsRowModifier(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
          Text(
            text = "Voice input mode",
            style = mobileHeadline,
            color = mobileText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
          )
          VoiceModeChoice(
            title = "Live",
            description = "Tap once to keep the mic open and stream turns continuously.",
            selected = voiceInputMode == VoiceInputMode.Live,
            onClick = {
              viewModel.setVoiceInputMode(VoiceInputMode.Live)
              viewModel.setMicEnabled(false)
            },
          )
          HorizontalDivider(color = mobileBorder)
          VoiceModeChoice(
            title = "Push to Talk",
            description = "Hold to talk, release to send.",
            selected = voiceInputMode == VoiceInputMode.PushToTalk,
            onClick = {
              viewModel.setVoiceInputMode(VoiceInputMode.PushToTalk)
              viewModel.setMicEnabled(false)
            },
          )
        }
      }
      item {
        VoiceProfilePicker(
          options = assistantVoiceOptions,
          selectedKey = assistantVoiceSelection,
          onRefresh = viewModel::refreshAssistantVoiceOptions,
          onSelect = viewModel::setAssistantVoiceSelection,
        )
      }
      item {
        ListItem(
          modifier = Modifier.settingsRowModifier(),
          colors = listItemColors,
          headlineContent = { Text("Microphone permission", style = mobileHeadline) },
          supportingContent = {
            Text(
              if (micPermissionGranted) {
                "Granted. Required for Voice tab capture."
              } else {
                "Required for both Live and Push to Talk voice input."
              },
              style = mobileCallout,
            )
          },
          trailingContent = {
            Button(
              onClick = {
                if (micPermissionGranted) {
                  openAppSettings(context)
                } else {
                  micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
              },
              colors = settingsPrimaryButtonColors(),
              shape = RoundedCornerShape(14.dp),
            ) {
              Text(
                if (micPermissionGranted) "Manage" else "Grant",
                style = mobileCallout.copy(fontWeight = FontWeight.Bold),
              )
            }
          },
        )
      }

      item { HorizontalDivider(color = mobileBorder) }
      item { SettingsSectionTitle("MEDIA") }
      item {
        ListItem(
          modifier = Modifier.settingsRowModifier(),
          colors = listItemColors,
          headlineContent = { Text("Allow camera", style = mobileHeadline) },
          supportingContent = {
            Text(
              "Lets the companion scan QR codes and capture media when you request it.",
              style = mobileCallout,
            )
          },
          trailingContent = {
            Switch(
              checked = cameraEnabled,
              onCheckedChange = ::setCameraEnabledChecked,
            )
          },
        )
      }
      item {
        ListItem(
          modifier = Modifier.settingsRowModifier(),
          colors = listItemColors,
          headlineContent = { Text("Gallery access", style = mobileHeadline) },
          supportingContent = {
            Text(
              "Only needed when you want to read images from the phone gallery.",
              style = mobileCallout,
            )
          },
          trailingContent = {
            Button(
              onClick = {
                if (photosPermissionGranted) {
                  openAppSettings(context)
                } else {
                  photosPermissionLauncher.launch(photosPermission)
                }
              },
              colors = settingsPrimaryButtonColors(),
              shape = RoundedCornerShape(14.dp),
            ) {
              Text(
                if (photosPermissionGranted) "Manage" else "Grant",
                style = mobileCallout.copy(fontWeight = FontWeight.Bold),
              )
            }
          },
        )
      }

      item { HorizontalDivider(color = mobileBorder) }
      item { SettingsSectionTitle("APP") }
      item {
        ListItem(
          modifier = Modifier.settingsRowModifier(),
          colors = listItemColors,
          headlineContent = { Text("Prevent sleep", style = mobileHeadline) },
          supportingContent = {
            Text(
              "Keeps the screen awake while Claw Companion is open.",
              style = mobileCallout,
            )
          },
          trailingContent = {
            Switch(
              checked = preventSleep,
              onCheckedChange = viewModel::setPreventSleep,
            )
          },
        )
      }
      item { Text("Instance ID: $instanceId", style = mobileCallout.copy(fontFamily = FontFamily.Monospace), color = mobileTextSecondary) }
      item { Text("Version: $appVersion", style = mobileCallout, color = mobileTextSecondary) }

      item { Spacer(modifier = Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun SettingsSectionTitle(text: String) {
  Text(
    text,
    style = mobileCaption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    color = mobileAccent,
  )
}

@Composable
private fun VoiceProfilePicker(
  options: List<AssistantVoiceOption>,
  selectedKey: String,
  onRefresh: () -> Unit,
  onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val resolvedOptions =
    remember(options) {
      if (options.isEmpty()) {
        listOf(AssistantVoiceOption(key = "", label = "Automatic", detail = "Connect to a gateway to load voices"))
      } else {
        options
      }
    }
  val selectedOption =
    resolvedOptions.firstOrNull { it.key == selectedKey }
      ?: resolvedOptions.firstOrNull { it.key.isEmpty() }
      ?: resolvedOptions.first()

  Column(modifier = Modifier.settingsRowModifier().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Assistant voice", style = mobileHeadline, color = mobileText)
    Text(
      "Choose the playback voice used for chat and voice replies.",
      style = mobileCallout,
      color = mobileTextSecondary,
    )
    Box {
      Surface(
        onClick = { expanded = true },
        shape = RoundedCornerShape(14.dp),
        color = mobileSurfaceStrong,
        border = androidx.compose.foundation.BorderStroke(1.dp, mobileBorderStrong),
        shadowElevation = 0.dp,
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
          Text(selectedOption.label, style = mobileCallout.copy(fontWeight = FontWeight.SemiBold), color = mobileText)
          Text(selectedOption.detail, style = mobileCaption1, color = mobileTextSecondary)
        }
      }
      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.fillMaxWidth(0.94f),
      ) {
        resolvedOptions.forEach { option ->
          DropdownMenuItem(
            text = {
              Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.label, style = mobileCallout.copy(fontWeight = FontWeight.SemiBold), color = mobileText)
                Text(option.detail, style = mobileCaption1, color = mobileTextSecondary)
              }
            },
            onClick = {
              expanded = false
              onSelect(option.key)
            },
          )
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = onRefresh,
        colors = settingsSecondaryButtonColors(),
        shape = RoundedCornerShape(14.dp),
      ) {
        Text("Refresh voices", style = mobileCallout.copy(fontWeight = FontWeight.Bold))
      }
      Button(
        onClick = { onSelect("") },
        colors = settingsSecondaryButtonColors(),
        shape = RoundedCornerShape(14.dp),
      ) {
        Text("Use default", style = mobileCallout.copy(fontWeight = FontWeight.Bold))
      }
    }
  }
}

@Composable
private fun settingsTextFieldColors() =
  OutlinedTextFieldDefaults.colors(
    focusedContainerColor = mobileSurface,
    unfocusedContainerColor = mobileSurface,
    focusedBorderColor = mobileAccent,
    unfocusedBorderColor = mobileBorder,
    focusedTextColor = mobileText,
    unfocusedTextColor = mobileText,
    cursorColor = mobileAccent,
  )

private fun Modifier.settingsRowModifier() =
  this
    .fillMaxWidth()
    .border(width = 1.dp, color = mobileBorder, shape = RoundedCornerShape(14.dp))
    .background(mobileSurface, RoundedCornerShape(14.dp))

@Composable
private fun settingsPrimaryButtonColors() =
  ButtonDefaults.buttonColors(
    containerColor = mobileAccent,
    contentColor = Color.White,
    disabledContainerColor = mobileAccent.copy(alpha = 0.45f),
    disabledContentColor = Color.White.copy(alpha = 0.9f),
  )

@Composable
private fun settingsSecondaryButtonColors() =
  ButtonDefaults.buttonColors(
    containerColor = mobileSurfaceStrong,
    contentColor = mobileText,
    disabledContainerColor = mobileSurfaceStrong.copy(alpha = 0.5f),
    disabledContentColor = mobileTextSecondary,
  )

@Composable
private fun ThemeModeChoice(
  title: String,
  description: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  ListItem(
    modifier = Modifier.fillMaxWidth(),
    colors =
      ListItemDefaults.colors(
        containerColor = Color.Transparent,
        headlineColor = mobileText,
        supportingColor = mobileTextSecondary,
      ),
    headlineContent = { Text(title, style = mobileHeadline) },
    supportingContent = { Text(description, style = mobileCallout) },
    trailingContent = {
      RadioButton(selected = selected, onClick = onClick)
    },
  )
}

@Composable
private fun VoiceModeChoice(
  title: String,
  description: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  ListItem(
    modifier = Modifier.fillMaxWidth(),
    colors =
      ListItemDefaults.colors(
        containerColor = Color.Transparent,
        headlineColor = mobileText,
        supportingColor = mobileTextSecondary,
      ),
    headlineContent = { Text(title, style = mobileHeadline) },
    supportingContent = { Text(description, style = mobileCallout) },
    trailingContent = {
      RadioButton(selected = selected, onClick = onClick)
    },
  )
}

private fun Context.hasPermission(permission: String): Boolean {
  return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun openAppSettings(context: Context) {
  val intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", context.packageName, null),
    )
  context.startActivity(intent)
}
