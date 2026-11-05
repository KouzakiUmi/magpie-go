package com.magpie.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.magpie.android.R
import com.magpie.android.runtime.MagpieConfig
import com.magpie.android.runtime.MagpieEvent
import com.magpie.android.runtime.MagpiePhase
import com.magpie.android.runtime.MagpieRuntime
import com.magpie.android.runtime.MagpieStatus
import kotlinx.coroutines.launch

private data class InstallProgress(
    val step: String = "",
    val done: Long = 0,
    val total: Long = 0,
)

@Composable
fun MagpieApp(
    runtime: MagpieRuntime?,
    startConfig: MagpieConfig,
    onExit: () -> Unit,
    onShowWeb: (MagpieStatus) -> Unit = {},
    onHideWeb: () -> Unit = {},
) {
    if (runtime == null) {
        MessageScreen(
            title = stringResource(R.string.runtime_missing_title),
            body = stringResource(R.string.runtime_missing_body),
        )
        return
    }

    var status by remember(runtime) { mutableStateOf(runtime.status()) }
    var progress by remember(runtime) { mutableStateOf(InstallProgress()) }
    var operationRunning by remember(runtime) { mutableStateOf(false) }
    var operationFailed by remember(runtime) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(runtime) {
        runtime.events().collect { event ->
            when (event) {
                is MagpieEvent.Status -> status = event.status
                is MagpieEvent.Progress -> progress = InstallProgress(
                    step = event.step,
                    done = event.done,
                    total = event.total,
                )
                is MagpieEvent.Log -> Unit
            }
        }
    }

    fun launchOperation(operation: suspend () -> Result<Unit>) {
        if (operationRunning) return
        scope.launch {
            operationRunning = true
            operationFailed = false
            val result = operation()
            operationFailed = result.isFailure
            operationRunning = false
        }
    }

    if (status.phase != MagpiePhase.Running) {
        LaunchedEffect(status.phase) { onHideWeb() }
    }
    when (status.phase) {
        MagpiePhase.NotInstalled -> InstallScreen(
            busy = operationRunning,
            operationFailed = operationFailed,
            onInstall = { launchOperation { runtime.install() } },
        )

        MagpiePhase.Installing -> InstallingScreen(progress)

        MagpiePhase.Stopped, MagpiePhase.Error -> StartScreen(
            status = status,
            busy = operationRunning,
            operationFailed = operationFailed,
            onStart = { launchOperation { runtime.start(startConfig) } },
        )

        MagpiePhase.Starting -> LoadingScreen(
            title = stringResource(R.string.starting_title),
            body = stringResource(R.string.starting_body),
        )

        MagpiePhase.Stopping -> LoadingScreen(
            title = stringResource(R.string.stopping_title),
            body = stringResource(R.string.stopping_body),
        )

        MagpiePhase.Running -> {
            // 运行中不再用 Compose 承载 WebView：实测在该设备上
            // AndroidView 里的 WebView 永远加载不出页面。
            // 这里只留一个占位，真正的网页由 MainActivity 的原生容器显示。
            LaunchedEffect(status.pageUrl) {
                android.util.Log.i(
                    "MagpieUiState",
                    "Running pageUrl=${if (status.pageUrl.isNullOrBlank()) "null" else "set"}",
                )
                onShowWeb(status)
            }
            LoadingScreen(
                title = stringResource(R.string.starting_title),
                body = stringResource(R.string.starting_body),
            )
        }
    }
}

@Composable
private fun InstallScreen(
    busy: Boolean,
    operationFailed: Boolean,
    onInstall: () -> Unit,
) {
    MessageScreen(
        title = stringResource(R.string.install_title),
        body = stringResource(R.string.install_body),
    ) {
        if (operationFailed) OperationError()
        Button(
            onClick = onInstall,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.download_button))
        }
    }
}

@Composable
private fun InstallingScreen(progress: InstallProgress) {
    MessageScreen(
        title = stringResource(R.string.installing_title),
        body = stringResource(R.string.installing_body),
    ) {
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    R.string.install_progress_mb,
                    progress.done / 1_000_000f,
                    progress.total / 1_000_000f,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (progress.done > 0) {
                Text(
                    stringResource(R.string.install_downloaded_mb, progress.done / 1_000_000f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (progress.step.isNotBlank()) {
            Text(
                progress.step,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartScreen(
    status: MagpieStatus,
    busy: Boolean,
    operationFailed: Boolean,
    onStart: () -> Unit,
) {
    val isError = status.phase == MagpiePhase.Error
    MessageScreen(
        title = stringResource(if (isError) R.string.error_title else R.string.start_title),
        body = stringResource(if (isError) R.string.error_body else R.string.start_body),
    ) {
        status.error?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (operationFailed) OperationError()
        Button(
            onClick = onStart,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (isError) R.string.retry_button else R.string.start_button))
        }
    }
}

@Composable
private fun LoadingScreen(title: String, body: String) {
    MessageScreen(title = title, body = body) {
        AnimatedMagpie()
        Spacer(Modifier.height(8.dp))
        CircularProgressIndicator()
    }
}

@Composable
private fun AnimatedMagpie() {
    val transition = rememberInfiniteTransition(label = "magpie-hop")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "magpie-hop-progress",
    )
    val time = progress * 3f
    fun ease(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    val x = when {
        time < .25f -> 0f
        time < 1f -> 38f * ease((time - .25f) / .75f)
        time < 1.4f -> 38f
        time < 2f -> 38f * (1f - ease((time - 1.4f) / .6f))
        else -> 0f
    }
    val y = when {
        time < .25f -> 4f + 5f * ease(time / .25f)
        time < .65f -> 9f - 31f * ease((time - .25f) / .4f)
        time < 1f -> -22f + 22f * ease((time - .65f) / .35f)
        time < 1.15f -> 7f * ease(time - 1f)
        time < 1.4f -> 7f - 3f * ease((time - 1.15f) / .25f)
        time < 1.65f -> 4f - 26f * ease((time - 1.4f) / .25f)
        time < 2f -> -22f + 22f * ease((time - 1.65f) / .35f)
        time < 2.15f -> 7f * ease(time - 2f)
        time < 2.4f -> 7f - 3f * ease((time - 2.15f) / .25f)
        else -> 4f
    }
    val tilt = when {
        time in .25f..1f -> 7f * kotlin.math.sin((time - .25f) / .75f * kotlin.math.PI).toFloat()
        time in 1.4f..2f -> -7f * kotlin.math.sin((time - 1.4f) / .6f * kotlin.math.PI).toFloat()
        else -> 0f
    }
    val context = LocalContext.current
    val bitmap = remember(context) {
        android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.magpie_jump).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier
            .size(180.dp)
            .offset(x = x.dp, y = y.dp)
            .graphicsLayer {
                scaleX = if (time < 1.4f) 1f else -1f
                rotationZ = tilt
            },
        filterQuality = FilterQuality.None,
    )
}

@Composable
private fun OperationError() {
    Text(
        stringResource(R.string.operation_failed),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MessageScreen(
    title: String,
    body: String,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                content?.let {
                    Spacer(Modifier.height(4.dp))
                    it()
                }
            }
        }
    }
}
