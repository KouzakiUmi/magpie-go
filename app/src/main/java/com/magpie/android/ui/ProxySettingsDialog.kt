package com.magpie.android.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.magpie.android.R
import com.magpie.android.data.AutoProxyController
import com.magpie.android.data.ProxyAddress
import com.magpie.android.data.ProxySetting
import com.magpie.android.data.Reachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 半自动代理设置。
 *
 * ## 为什么是「半自动」而不是「全自动」
 *
 * 探测本身是可靠的（真机验证过），但**替用户悄悄改动 magpie 的全局代理**
 * 影响面太大：他可能已经在 magpie 的 Providers 页给单个服务商配了代理，
 * 全局值一改会牵连所有没单独配置的服务商。所以这里只做两件事：
 *
 * 1. 用户点「检测」——真的去探测，把结论摆出来；
 * 2. 用户点「应用」——才写入 magpie 并重启进程。
 *
 * 不探测就不改、不点应用就不改，这是这个界面存在的前提。
 *
 * ## 探测目标
 *
 * 默认取几个常用的境外服务商地址。这些只是「够不够用」的判据，
 * 不代表用户一定在用它们；用户也可以自己填。
 */
class ProxySettingsDialog(
    private val context: Context,
    private val controller: AutoProxyController,
    private val onApplied: () -> Unit,
    /** 取已配置服务商的主机名；由调用方提供，避免这里直接依赖 runtime。 */
    private val providerHostsAccessor: (suspend () -> List<String>)? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dialog: Dialog

    private lateinit var targetInput: EditText
    /** 已配置服务商的目标列表；为空时只用输入框。 */
    private lateinit var targetPicker: android.widget.Spinner
    private var providerTargets: List<String> = emptyList()
    private lateinit var proxyInput: EditText
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var detectButton: Button
    private lateinit var applyButton: Button
    private lateinit var clearButton: Button
    private lateinit var rememberCheck: CheckBox

    /** 最近一次检测的结论；null 表示还没检测或目标已改。 */
    private var lastOutcome: Reachability? = null
    private var detectionSerial = 0L
    private var running: Job? = null

    fun show() {
        dialog = Dialog(context).apply {
            setTitle(R.string.proxy_dialog_title)
            val content = buildContent()
            setContentView(ScrollView(context).apply { addView(content) })
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        refreshCurrent()
        loadProviderTargets()
    }

    fun dismiss() {
        running?.cancel()
        scope.cancel()
        if (::dialog.isInitialized && dialog.isShowing) dialog.dismiss()
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        statusView = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
        }
        root.addView(statusView, matchWidth())

        root.addView(
            label(R.string.proxy_detect_target),
            marginTop(dp(14)),
        )
        // 优先用已配置服务商的地址：用户已经告诉 magpie 自己在用哪些服务，
        // 拿它们的地址去检测才有意义。没有服务商时退回手填。
        targetPicker = android.widget.Spinner(context).apply {
            visibility = View.GONE
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    providerTargets.getOrNull(position)?.let {
                        targetInput.setText(it)
                        lastOutcome = null
                        applyButton.isEnabled = false
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        root.addView(targetPicker, matchWidth())

        targetInput = EditText(context).apply {
            setText(DEFAULT_TARGET)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        root.addView(targetInput, matchWidth())

        root.addView(label(R.string.proxy_candidate), marginTop(dp(10)))
        proxyInput = EditText(context).apply {
            hint = "127.0.0.1:7890"
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val inputWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                detectionSerial++
                running?.cancel()
                lastOutcome = null
                applyButton.isEnabled = false
            }
        }
        targetInput.addTextChangedListener(inputWatcher)
        proxyInput.addTextChangedListener(inputWatcher)
        root.addView(proxyInput, matchWidth())

        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, marginTop(dp(10)))

        resultView = TextView(context).apply {
            textSize = 13f
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(resultView, matchWidth())

        detectButton = Button(context).apply {
            setText(R.string.proxy_detect)
            setOnClickListener { runDetection() }
        }
        root.addView(detectButton, marginTop(dp(8)))

        applyButton = Button(context).apply {
            setText(R.string.proxy_apply)
            isEnabled = false
            setOnClickListener { applyDetected() }
        }
        root.addView(applyButton, matchWidth())

        rememberCheck = CheckBox(context).apply {
            setText(R.string.proxy_remember)
            textSize = 12f
            isChecked = true
        }
        root.addView(rememberCheck, marginTop(dp(6)))

        clearButton = Button(context).apply {
            setText(R.string.proxy_follow_system)
            setOnClickListener { resetProxy("") }
        }
        root.addView(clearButton, marginTop(dp(6)))

        root.addView(
            TextView(context).apply {
                setText(R.string.proxy_scope_note)
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
            },
            marginTop(dp(10)),
        )

        root.addView(
            Button(context).apply {
                setText(android.R.string.cancel)
                setOnClickListener { dialog.dismiss() }
            },
            marginTop(dp(4)),
        )

        return root
    }

    /** 读取已配置服务商并填进下拉；没有就保持隐藏。 */
    private fun loadProviderTargets() {
        scope.launch {
            val hosts = withContext(Dispatchers.IO) {
                runCatching { providerHostsAccessor?.invoke() }.getOrNull().orEmpty()
            }
            if (hosts.isEmpty()) {
                targetPicker.visibility = View.GONE
                return@launch
            }
            providerTargets = hosts.map { host ->
                if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
            }
            targetPicker.adapter = android.widget.ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                providerTargets,
            )
            targetPicker.visibility = View.VISIBLE
            targetInput.setText(providerTargets.first())
        }
    }

    private fun refreshCurrent() {
        scope.launch {
            val current = runCatching { controller.current() }
            statusView.text = current.fold(
                onSuccess = { context.getString(R.string.proxy_current, describe(it)) },
                onFailure = { context.getString(R.string.proxy_current_unknown) },
            )
        }
    }

    private fun describe(setting: ProxySetting): String = when (setting) {
        ProxySetting.FollowSystem -> context.getString(R.string.proxy_follow_system_short)
        ProxySetting.Direct -> context.getString(R.string.proxy_direct_short)
        is ProxySetting.Custom -> setting.url
    }

    private fun runDetection() {
        val target = targetInput.text.toString().trim()
        val candidate = proxyInput.text.toString().trim().ifEmpty { null }

        if (candidate != null && !ProxyAddress.validateSetting(candidate)) {
            resultView.setText(R.string.proxy_invalid_candidate)
            resultView.setTextColor(Color.parseColor("#B3261E"))
            lastOutcome = null
            applyButton.isEnabled = false
            return
        }

        val serial = ++detectionSerial
        running?.cancel()
        setBusy(true)
        resultView.text = ""
        lastOutcome = null
        applyButton.isEnabled = false

        running = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { controller.probe(target, PROBE_TIMEOUT_MS, candidate) }.getOrNull()
            }
            if (serial != detectionSerial) return@launch
            setBusy(false)
            if (outcome == null) {
                resultView.setText(R.string.proxy_detect_failed)
                resultView.setTextColor(Color.parseColor("#B3261E"))
                return@launch
            }
            lastOutcome = outcome
            renderOutcome(outcome, candidate)
        }
    }

    private fun renderOutcome(outcome: Reachability, candidate: String?) {
        when (outcome) {
            is Reachability.Direct -> {
                resultView.text = context.getString(R.string.proxy_result_direct, outcome.latencyMs)
                resultView.setTextColor(Color.parseColor("#1B6E3C"))
                applyButton.isEnabled = false
            }
            is Reachability.ViaProxy -> {
                resultView.text = context.getString(R.string.proxy_result_via, outcome.latencyMs)
                resultView.setTextColor(Color.parseColor("#8A5A00"))
                applyButton.isEnabled = true
            }
            is Reachability.Unreachable -> {
                resultView.text = context.getString(R.string.proxy_result_unreachable)
                resultView.setTextColor(Color.parseColor("#B3261E"))
                applyButton.isEnabled = false
            }
        }
    }

    private fun applyDetected() {
        val outcome = lastOutcome as? Reachability.ViaProxy ?: return
        val candidate = proxyInput.text.toString().trim()
        if (!ProxyAddress.validateSetting(candidate)) return
        commit(ProxySetting.Custom(candidate), restart = true)
    }

    private fun resetProxy(value: String) {
        commit(ProxySetting.parse(value), restart = true)
    }

    private fun commit(setting: ProxySetting, restart: Boolean) {
        setBusy(true)
        resultView.setText(R.string.proxy_applying)
        resultView.setTextColor(Color.parseColor("#666666"))
        scope.launch {
            val result = withContext(Dispatchers.IO) { controller.apply(setting) }
            setBusy(false)
            result.fold(
                onSuccess = {
                    resultView.setText(R.string.proxy_applied)
                    resultView.setTextColor(Color.parseColor("#1B6E3C"))
                    refreshCurrent()
                    onApplied()
                },
                onFailure = { error ->
                    resultView.setText(
                        context.getString(R.string.proxy_apply_failed, error.message ?: ""),
                    )
                    resultView.setTextColor(Color.parseColor("#B3261E"))
                },
            )
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        detectButton.isEnabled = !busy
        clearButton.isEnabled = !busy
        targetInput.isEnabled = !busy
        proxyInput.isEnabled = !busy
        targetPicker.isEnabled = !busy
        if (busy) applyButton.isEnabled = false
    }

    private fun label(resId: Int) = TextView(context).apply {
        setText(resId)
        textSize = 12f
        setTextColor(Color.parseColor("#444444"))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun marginTop(px: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val PROBE_TIMEOUT_MS = 6_000L
        const val DEFAULT_TARGET = "https://api.anthropic.com"
    }
}
