package com.movtery.zalithlauncher.ui.screens.content.download

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.google.gson.JsonSyntaxException
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.download.game.optifine.CantFetchingOptiFineUrlException
import com.movtery.zalithlauncher.game.download.jvm_server.JvmCrashException
import com.movtery.zalithlauncher.game.version.download.DownloadFailedException
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.notification.NotificationManager
import com.movtery.zalithlauncher.ui.components.SimpleAlertDialog
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.content.download.common.GameInstallOperation
import com.movtery.zalithlauncher.ui.screens.content.download.common.GameInstallingDialog
import com.movtery.zalithlauncher.ui.screens.content.download.game.DownloadGameWithAddonScreen
import com.movtery.zalithlauncher.ui.screens.content.download.game.DownloadGameWithAddonScreenKey
import com.movtery.zalithlauncher.ui.screens.content.download.game.SelectGameVersionScreen
import com.movtery.zalithlauncher.ui.screens.content.download.game.SelectGameVersionScreenKey
import com.movtery.zalithlauncher.ui.screens.content.download.game.downloadGameBackStack
import com.movtery.zalithlauncher.ui.screens.content.download.game.downloadGameScreenKey
import com.movtery.zalithlauncher.ui.screens.navigateTo
import com.movtery.zalithlauncher.utils.logging.Logger.lError
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

@Serializable
data object DownloadGameScreenKey: NestedNavKey {
    override fun isLastScreen(): Boolean = downloadGameBackStack.size <= 1
}

@Composable
fun DownloadGameScreen() {
    val currentKey = downloadGameBackStack.lastOrNull()

    LaunchedEffect(currentKey) {
        downloadGameScreenKey = currentKey
    }

    var gameInstallOperation by remember { mutableStateOf<GameInstallOperation>(GameInstallOperation.None) }
    GameInstallOperation(
        gameInstallOperation = gameInstallOperation,
        updateOperation = { gameInstallOperation = it },
        onInstalled = {
            VersionsManager.refresh()
        }
    )

    NavDisplay(
        backStack = downloadGameBackStack,
        modifier = Modifier.fillMaxSize(),
        onBack = {
            val key = downloadGameBackStack.lastOrNull()
            if (key is NestedNavKey && !key.isLastScreen()) return@NavDisplay
            downloadGameBackStack.removeLastOrNull()
        },
        entryProvider = entryProvider {
            entry<SelectGameVersionScreenKey> {
                SelectGameVersionScreen { versionString ->
                    downloadGameBackStack.navigateTo(DownloadGameWithAddonScreenKey(versionString))
                }
            }
            entry<DownloadGameWithAddonScreenKey> {
                val context = LocalContext.current
                DownloadGameWithAddonScreen(it) { info ->
                    if (gameInstallOperation !is GameInstallOperation.None) {
                        //不是带安装状态，拒绝此次安装
                        return@DownloadGameWithAddonScreen
                    }
                    gameInstallOperation = if (!NotificationManager.checkNotificationEnabled(context)) {
                        //警告通知权限
                        GameInstallOperation.WarningForNotification(info)
                    } else {
                        GameInstallOperation.Install(info)
                    }
                }
            }
        }
    )
}

@Composable
private fun GameInstallOperation(
    gameInstallOperation: GameInstallOperation,
    updateOperation: (GameInstallOperation) -> Unit = {},
    onInstalled: () -> Unit = {}
) {
    val context = LocalContext.current

    when (gameInstallOperation) {
        is GameInstallOperation.None -> {}
        is GameInstallOperation.WarningForNotification -> {
            val requestPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                    if (isGranted) {
                        //权限被授予，开始安装
                        updateOperation(GameInstallOperation.Install(gameInstallOperation.info))
                    } else {
                        updateOperation(GameInstallOperation.None)
                    }
                }

            SimpleAlertDialog(
                title = stringResource(R.string.notification_title),
                text = {
                    Text(text = stringResource(R.string.notification_data_jvm_service_message))
                },
                confirmText = stringResource(R.string.notification_request),
                dismissText = stringResource(R.string.notification_ignore),
                onConfirm = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        //13- 跳转至设置，让用户自行开启通知权限
                        NotificationManager.openNotificationSettings(context)
                        updateOperation(GameInstallOperation.None)
                    } else {
                        //安卓 13+ 可以直接弹出通知权限申请
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onCancel = {
                    updateOperation(GameInstallOperation.Install(gameInstallOperation.info))
                },
                onDismissRequest = {
                    updateOperation(GameInstallOperation.None)
                }
            )
        }
        is GameInstallOperation.Install -> {
            val info = gameInstallOperation.info
            val installer = remember(info) { GameInstaller(context, info) }

            LaunchedEffect(info) {
                installer.installGame(
                    onInstalled = {
                        onInstalled()
                        updateOperation(GameInstallOperation.Success)
                    },
                    onError = { th ->
                        updateOperation(GameInstallOperation.Error(th))
                    }
                )
            }

            //安装游戏的弹窗
            val installGame = installer.tasksFlow.collectAsState()
            if (installGame.value.isNotEmpty()) {
                GameInstallingDialog(
                    title = stringResource(R.string.download_game_install_title),
                    tasks = installGame.value,
                    onDismissRequest = {
                        //取消安装游戏
                        installer.cancelInstall()
                        updateOperation(GameInstallOperation.None)
                    }
                )
            }
        }
        is GameInstallOperation.Error -> {
            val th = gameInstallOperation.th
            lError("Failed to download the game!", th)
            val message = when (th) {
                is HttpRequestTimeoutException, is SocketTimeoutException -> stringResource(R.string.error_timeout)
                is UnknownHostException, is UnresolvedAddressException -> stringResource(R.string.error_network_unreachable)
                is ConnectException -> stringResource(R.string.error_connection_failed)
                is SerializationException, is JsonSyntaxException -> stringResource(R.string.error_parse_failed)
                is CantFetchingOptiFineUrlException -> stringResource(R.string.download_install_error_cant_fetch_optifine_download_url)
                is JvmCrashException -> stringResource(R.string.download_install_error_jvm_crash, th.code)
                is DownloadFailedException -> stringResource(R.string.download_install_error_download_failed)
                else -> {
                    val errorMessage = th.localizedMessage ?: th.message ?: th::class.qualifiedName ?: "Unknown error"
                    stringResource(R.string.error_unknown, errorMessage)
                }
            }
            val dismiss = {
                updateOperation(GameInstallOperation.None)
            }
            AlertDialog(
                onDismissRequest = dismiss,
                title = {
                    Text(text = stringResource(R.string.download_install_error_title))
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = stringResource(R.string.download_install_error_message))
                        Text(text = message)
                    }
                },
                confirmButton = {
                    Button(onClick = dismiss) {
                        Text(text = stringResource(R.string.generic_confirm))
                    }
                }
            )
        }
        is GameInstallOperation.Success -> {
            SimpleAlertDialog(
                title = stringResource(R.string.download_install_success_title),
                text = stringResource(R.string.download_install_success_message)
            ) {
                updateOperation(GameInstallOperation.None)
            }
        }
    }
}