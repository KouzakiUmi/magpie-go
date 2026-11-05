package com.magpie.android.ui

import android.graphics.Color
import android.os.Bundle
import android.content.Intent
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.magpie.android.runtime.AndroidMagpieRuntime
import com.magpie.android.runtime.MagpiePhase
import com.magpie.android.R
import com.magpie.android.runtime.MagpieConfig
import com.magpie.android.runtime.MagpieStatus

/**
 * 主界面。
 *
 * 结构上分两段：
 * - 安装 / 启动 / 错误这些状态用 Compose 画（[MagpieApp]）；
 * - **运行中**改用原生容器承载 WebView（[MagpieWebContainer]）。
 *
 * 为什么运行中要绕开 Compose：实测在小米 MIUI 14 / Android 16 上，把 WebView
 * 放进 Compose 的 `AndroidView` 之后，`factory` 正常执行、视图已 attach 且有尺寸，
 * 但 `loadUrl` 虽"成功返回"，WebView 内部 `url` 恒为 null、`progress` 恒为 0，
 * 页面永远空白。同一台机器上纯 Activity 直接持有 WebView 则完全正常。
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var contentArea: FrameLayout
    private lateinit var toolBar: android.view.View
    private var proxyDialog: ProxySettingsDialog? = null
    private var runtimeInstance: com.magpie.android.runtime.MagpieRuntime? = null
    private var runtimeProvider: MagpieRuntimeProvider? = null
    private var startConfig = MagpieConfig()
    private lateinit var composeHost: ComposeView
    private var composeInitialized = false
    private val themePreferenceState by lazy {
        androidx.compose.runtime.mutableStateOf(ThemePreferenceStore.get(this))
    }
    private var themePreference = ThemePreference.FollowSystem
    /** 渲染进程崩溃的次数；变化后强制重建 WebView 容器。 */
    private var webRenderGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 根容器分两层：上面是网页（或 Compose 状态页），下面是 App 自己的工具条。
        // 把工具条放在网页之外，才能彻底避免和 magpie 页面里的按钮撞位置——
        // 它的顶栏内容会随页面变化（「更新」按钮时有时无），浮在上面必然冲突。
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        contentArea = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        column.addView(
            contentArea,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f },
        )
        toolBar = buildToolBar()
        column.addView(toolBar)
        setContentView(column)
        root = contentArea

        runtimeInstance = (application as? MagpieRuntimeProvider)?.runtime()
        val runtime = application as? MagpieRuntimeProvider
        runtimeProvider = runtime
        if (runtime == null) {
            root.addView(
                android.widget.TextView(this).apply {
                    text = getString(R.string.runtime_missing_title)
                    gravity = Gravity.CENTER
                    setTextColor(Color.DKGRAY)
                },
            )
            return
        }

        startConfig = (application as? StartConfigProvider)?.config() ?: MagpieConfig()
        themePreference = ThemePreferenceStore.get(this)

        // 用显式的 ComposeView 而不是 setContent：后者会把 Activity 的内容视图
        // 整个换掉，之后 addView 加的原生视图会挂到被替换的旧容器上，看不见。
        composeHost = ComposeView(this)
        applyTheme()
        root.addView(composeHost, FrameLayout.LayoutParams(-1, -1))

    }

    private fun applyTheme() {
        if (!composeInitialized) {
            composeHost.setContent {
                MagpieTheme(preference = themePreferenceState.value) {
                    MagpieApp(
                        runtime = runtimeProvider?.runtime(),
                        startConfig = startConfig,
                        onExit = ::finish,
                        onShowWeb = ::showWeb,
                        onHideWeb = ::removeWebContainer,
                        onShowExtras = ::showExtraComponents,
                    )
                }
            }
            composeInitialized = true
        } else {
            themePreferenceState.value = themePreference
        }
        updateToolbarColors()
        contentArea.findViewWithTag<MagpieWebContainer>(WEB_TAG)?.applyThemePreference(themePreference)
    }

    private fun isDarkTheme(): Boolean = when (themePreference) {
        ThemePreference.Dark -> true
        ThemePreference.Light -> false
        ThemePreference.FollowSystem ->
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateToolbarColors() {
        val dark = isDarkTheme()
        toolBar.setBackgroundColor(Color.parseColor(if (dark) "#182238" else "#F7F7F8"))
        val iconColor = Color.parseColor(if (dark) "#E2F2FF" else "#3C4043")
        val labelColor = Color.parseColor(if (dark) "#C6D2E4" else "#5F6368")
        fun tint(view: android.view.View) {
            if (view is android.widget.TextView) {
                view.setTextColor(if (view.tag == "toolbar_label") labelColor else iconColor)
            }
            if (view is android.view.ViewGroup) {
                repeat(view.childCount) { index -> tint(view.getChildAt(index)) }
            }
        }
        tint(toolBar)
    }

    private fun showAbout() {
        AboutDialog.show(
            this,
            themePreference,
            onConfigure = {
                contentArea.findViewWithTag<MagpieWebContainer>(WEB_TAG)?.openSettings()
            },
        ) { preference ->
            themePreference = preference
            ThemePreferenceStore.set(this, preference)
            applyTheme()
            contentArea.findViewWithTag<MagpieWebContainer>(WEB_TAG)
                ?.applyThemePreference(preference, persist = true)
        }
    }

    /** 用原生容器显示 magpie 的管理页。 */
    private fun showWeb(status: MagpieStatus) {
        if (status.pageUrl.isNullOrBlank()) return
        // 已经显示过就不再重复挂载
        val existing = root.findViewWithTag<MagpieWebContainer>(WEB_TAG)
        android.util.Log.i("MagpieWeb", "showWeb existing=${existing != null} childCount=${root.childCount}")
        if (existing != null) return

        val container = MagpieWebContainer(
            this,
            status,
            onBack = { handleBack() },
            themePreference = themePreference,
            onRendererGone = {
                // 渲染进程崩溃：立刻拆掉旧容器并重建。
                //
                // 不能只拆不等——tag 留着会让下一次 showWeb 直接跳过，
                // 而 pageUrl 没变又不会触发 Compose 的 LaunchedEffect，
                // 结果界面永远停在占位页（实测过）。
                root.findViewWithTag<MagpieWebContainer>(WEB_TAG)?.let { root.removeView(it) }
                webRenderGeneration++
                android.util.Log.w("MagpieWeb", "renderer gone, rebuilding generation=$webRenderGeneration")
                // 崩溃发生在 WebView 的线程回调里，等这一帧结束再重建
                root.post { showWeb(status) }
            },
        )
        container.tag = WEB_TAG
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        // Compose 的根与它不是同一棵子树，这里显式提到最前避免被盖住。
        container.bringToFront()
        container.elevation = 10f

    }

    /**
     * 右下角的诊断入口。
     *
     * 为什么要有：用户遇到「打不开」「连不上」时，界面只能说一句结论，
     * 而定位问题需要看到运行日志。让用户自己抓 logcat 不现实，
     * 所以点一下就把环境与日志打成文件，直接走系统分享发出来。
     *
     * 位置贴左下角（右上角是 magpie 的刷新/齿轮，左上角是它的 logo），不压正文；
     * 导出内容全部经过脱敏，不含 web key 与凭据。
     */
    /**
     * App 自己的工具条。
     *
     * 放在网页下面而不是浮在网页上：magpie 的顶栏有刷新、齿轮、「更新」等按钮，
     * 位置随页面状态变化，浮在上面的入口一定会和其中某个撞上。
     * 独立成条后两者互不干扰，也不遮挡任何网页内容。
     */
    private fun buildToolBar(): android.view.View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.parseColor("#F7F7F8"))
            tag = TOOLBAR_TAG

            addView(
                toolButton(R.string.tools_menu, "☰") { showToolsMenu() },
                android.widget.LinearLayout.LayoutParams(0, dp(44)).apply { weight = 1f },
            )
            addView(
                toolButton(R.string.proxy_dialog_title, "⇄") { showProxySettings() },
                android.widget.LinearLayout.LayoutParams(0, dp(44)).apply { weight = 1f },
            )
            addView(
                toolButton(R.string.about, "ⓘ") { showAbout() },
                android.widget.LinearLayout.LayoutParams(0, dp(44)).apply { weight = 1f },
            )
        }

        // 底部手势条内边距
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    or androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(dp(8), dp(4), dp(8), dp(4) + bars.bottom)
            insets
        }
        return bar
    }

    /** 工具条上的一个按钮：图标 + 文字，跟着页面的中性配色。 */
    private fun toolButton(labelRes: Int, icon: String, onClick: () -> Unit): android.view.View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                android.widget.TextView(this@MainActivity).apply {
                    tag = "toolbar_icon"
                    text = icon
                    textSize = 16f
                    setTextColor(Color.parseColor(if (isDarkTheme()) "#E2F2FF" else "#3C4043"))
                    gravity = Gravity.CENTER
                },
            )
            addView(
                android.widget.TextView(this@MainActivity).apply {
                    tag = "toolbar_label"
                    setText(labelRes)
                    textSize = 10f
                    setTextColor(Color.parseColor(if (isDarkTheme()) "#C6D2E4" else "#5F6368"))
                    gravity = Gravity.CENTER
                },
            )
        }
    }

    /** 「菜单」：网关控制与重置。 */
    private fun showToolsMenu() {
        val menuContext = android.view.ContextThemeWrapper(
            this,
            if (isDarkTheme()) android.R.style.Theme_Material else android.R.style.Theme_Material_Light,
        )
        val popup = android.widget.PopupMenu(menuContext, toolBar.findViewWithTag<android.view.View>(TOOLBAR_TAG) ?: toolBar)
        val running = runtimeInstance?.status()?.phase == MagpiePhase.Running
        popup.menu.add(0, MENU_RESTART, 0, R.string.gateway_restart).isEnabled = running
        popup.menu.add(0, MENU_STOP, 1, R.string.gateway_stop).isEnabled = running
        popup.menu.add(1, MENU_RESET, 2, R.string.runtime_reset)
        popup.menu.add(1, MENU_EXPORT_LOGS, 3, R.string.export_logs)
        popup.menu.add(1, MENU_COMPONENTS, 4, R.string.extra_components)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RESTART -> { restartGateway(); true }
                MENU_STOP -> { stopGateway(); true }
                MENU_RESET -> { confirmReset(); true }
                MENU_EXPORT_LOGS -> { exportDiagnostics(); true }
                MENU_COMPONENTS -> { showExtraComponents(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showExtraComponents() {
        if (componentDownloadJob?.isActive == true) {
            showComponentProgress()
            return
        }
        val installed = com.magpie.android.runtime.BunRuntimeManager.isInstalled(this)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.extra_components)
            .setMessage(if (installed) R.string.bun_installed_body else R.string.bun_download_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (installed) R.string.bun_reinstall else R.string.bun_download) { _, _ ->
                downloadExtraComponents()
            }
            .show()
    }

    private fun showComponentProgress() {
        if (componentProgress?.isShowing == true) return
        componentProgress = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.extra_components)
            .setMessage(R.string.bun_downloading)
            .setCancelable(false)
            .show()
    }

    private fun downloadExtraComponents() {
        if (componentDownloadJob?.isActive == true) {
            showComponentProgress()
            return
        }
        showComponentProgress()
        val app = application
        componentDownloadJob = componentScope.launch {
            try {
            val result = withContext(Dispatchers.IO) {
                com.magpie.android.runtime.BunRuntimeManager.install(app)
            }
            componentProgress?.dismiss()
            componentProgress = null
            result.onSuccess {
                val runtime = (app as? MagpieRuntimeProvider)?.runtime()
                val config = (app as? StartConfigProvider)?.config()
                val running = runtime?.status()?.phase == MagpiePhase.Running
                android.widget.Toast.makeText(
                    app,
                    if (running) R.string.bun_installed_restarting else R.string.bun_installed_next_start,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                if (running && config != null && runtime != null) {
                    withContext(Dispatchers.IO) {
                        runtime.stop().let { stopped -> if (stopped.isSuccess) runtime.start(config) else stopped }
                    }
                }
            }.onFailure { error ->
                android.widget.Toast.makeText(
                    app,
                    error.message ?: getString(R.string.bun_download_failed),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            } finally {
                componentDownloadJob = null
            }
        }
    }

    /**
     * 半自动代理设置。
     *
     * 只提供「检测」和「应用」，不会自己改设置——magpie 的全局代理一改
     * 会牵连所有没单独配置代理的服务商，必须由用户看着结论决定。
     */
    private fun showProxySettings() {
        proxyDialog?.dismiss()
        val controller = com.magpie.android.data.DefaultAutoProxyController(this)
        proxyDialog = ProxySettingsDialog(
            context = this,
            controller = controller,
            onApplied = {
                // 代理改完 magpie 会重启，状态流会推新状态
            },
            providerHostsAccessor = {
                (runtimeInstance as? com.magpie.android.runtime.AndroidMagpieRuntime)
                    ?.providerHosts()
                    .orEmpty()
            },
        ).also { it.show() }
    }

    /** 重启网关：停掉再拉起，不碰配置。 */
    private fun restartGateway() {
        val runtime = runtimeInstance ?: return
        val config = (application as? StartConfigProvider)?.config() ?: return
        lifecycleScope.launch {
            toast(R.string.gateway_restarting)
            val result = withContext(Dispatchers.IO) {
                runtime.stop().let { if (it.isSuccess) runtime.start(config) else it }
            }
            if (result.isFailure) toast(R.string.gateway_restart_failed)
        }
    }

    /** 停止网关。数据和配置都保留，下次启动直接可用。 */
    private fun stopGateway() {
        val runtime = runtimeInstance ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runtime.stop() }
            if (result.isSuccess) {
                removeWebContainer()
                toast(R.string.gateway_stopped)
            } else {
                toast(R.string.gateway_stop_failed)
            }
        }
    }

    /**
     * 重置运行时。
     *
     * `reset(keepData = false)` 会删掉整个私有运行目录——供应商配置、API key、
     * 登录态全部丢失且不可恢复，所以必须先确认并把后果写清楚。
     */
    private fun confirmReset() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.runtime_reset)
            .setMessage(R.string.runtime_reset_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.runtime_reset_action) { _, _ ->
                val runtime = runtimeInstance ?: return@setPositiveButton
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runtime.reset(keepData = false) }
                    if (result.isSuccess) {
                        removeWebContainer()
                        toast(R.string.runtime_reset_done)
                    } else {
                        toast(R.string.runtime_reset_failed)
                    }
                }
            }
            .show()
    }

    private fun removeWebContainer() {
        contentArea.findViewWithTag<MagpieWebContainer>(WEB_TAG)?.let { contentArea.removeView(it) }
    }

    private fun toast(resId: Int) {
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun exportDiagnostics() {
        val file = com.magpie.android.diagnostics.DiagnosticsExporter.export(this)
        if (file == null) {
            android.widget.Toast.makeText(this, R.string.export_logs_failed, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                this@MainActivity,
                "${packageName}.files",
                file,
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(share, getString(R.string.export_logs)))
        }.onFailure {
            android.widget.Toast.makeText(this, R.string.export_logs_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚在系统设置里改了电池豁免，回来时把通知里的过期入口去掉
        AndroidMagpieRuntime.getInstance(this).activeService()?.refreshNotification()
        applyTheme()
    }

    @Deprecated("老式返回键")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        val web = root.findViewWithTag<MagpieWebContainer>(WEB_TAG)
        if (web != null && web.canGoBack()) {
            web.goBack()
            return
        }
        // 返回只把界面退到后台：WebView、前台服务和 magpie 进程都继续保留。
        moveTaskToBack(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == MagpieWebContainer.REQUEST_BACKUP_FILE) {
            if (resultCode == RESULT_OK && data?.data == null && data?.clipData == null) {
                toast(R.string.backup_import_invalid)
                contentArea.findViewWithTag<MagpieWebContainer>(WEB_TAG)
                    ?.handleFileChooserResult(RESULT_CANCELED, null)
                return
            }
            val uris = buildList {
                data?.data?.let { add(it) }
                data?.clipData?.let { clips -> repeat(clips.itemCount) { i -> add(clips.getItemAt(i).uri) } }
            }.mapNotNull { BackupImport.prepare(this, it) }.toTypedArray()
            if (resultCode == RESULT_OK && uris.isEmpty()) toast(R.string.backup_import_invalid)
            contentArea.findViewWithTag<MagpieWebContainer>(WEB_TAG)
                ?.handleFileChooserResult(if (uris.isEmpty()) RESULT_CANCELED else resultCode, data, uris)
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themePreference == ThemePreference.FollowSystem) applyTheme()
    }

    override fun onDestroy() {
        proxyDialog?.dismiss()
        proxyDialog = null
        componentProgress?.dismiss()
        componentProgress = null
        super.onDestroy()
    }

    private companion object {
        private val componentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var componentDownloadJob: Job? = null
        private var componentProgress: android.app.AlertDialog? = null

        const val WEB_TAG = "magpie_web"
        const val DIAG_TAG = "magpie_diag"
        const val TOOLBAR_TAG = "magpie_toolbar"
        const val MENU_EXPORT = 1
        const val MENU_PROXY = 2
        const val MENU_RESTART = 3
        const val MENU_STOP = 4
        const val MENU_RESET = 5
        const val MENU_EXPORT_LOGS = 6
        const val MENU_COMPONENTS = 7
    }
}
