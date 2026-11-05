package com.magpie.android.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.magpie.android.R
import com.magpie.android.runtime.MagpiePhase
import com.magpie.android.runtime.MagpieStatus
import java.io.File

/**
 * 原生视图承载的 magpie 管理界面。
 *
 * ## 为什么不用 Compose 的 AndroidView
 *
 * 实测（小米 MIUI 14 / Android 16）把 WebView 放进 Compose 的 `AndroidView` 后：
 * - `factory` 正常执行，视图 `isAttachedToWindow=true`、尺寸 `1200x2322`、可见；
 * - 但 `loadUrl` 虽然"成功返回"，WebView 内部的 `url` 始终是 `null`、`progress=0`；
 * - 页面永远空白，重试两次也一样。
 *
 * 同一台机器上，**纯 Activity 直接持有 WebView** 时一切正常（能加载并渲染出
 * magpie 首页的真实内容）。所以这里改用原生 `FrameLayout` 承载 WebView，
 * 顶部栏反过来用 `ComposeView` 嵌进原生布局，绕开 WebView 经过 Compose 的桥。
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class MagpieWebContainer(
    context: Context,
    private val status: MagpieStatus,
    private val onBack: () -> Unit,
    /**
     * 渲染进程崩溃时的回调。
     *
     * 不处理的后果（实测）：chromium 检测到 `Renderer process crash detected` 后，
     * 整个 WebView 会从视图树里消失，界面回落成占位页，用户必须杀掉 App 才能恢复。
     * 交给上层重建更稳——这一层不该自己 destroy 再 new，容易漏掉 Cookie 与状态。
     */
    private val onRendererGone: () -> Unit = {},
) : FrameLayout(context) {

    private fun brandedAsset(request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.host != "127.0.0.1" && url.host != "localhost") return null
        val name = when (url.path) {
            "/icons/apple-touch-icon.png" -> "branding/apple-touch-icon.png"
            "/icons/mascot-head.png" -> "branding/mascot-head.png"
            "/icons/magpie-jump.png" -> "branding/magpie-jump.png"
            "/icons/magpie-smile.png" -> "branding/magpie-smile.png"
            "/icons/favicon.svg" -> "branding/favicon.svg"
            "/plugins/redact/pica_wave.png" -> "branding/pica_wave.png"
            "/plugins/redact/xq_light.jpg" -> "branding/xq_light.jpg"
            "/plugins/redact/xq_dark.jpg" -> "branding/xq_dark.jpg"
            "/plugins/redact/xq_light.webm" -> "branding/xq_light.webm"
            "/plugins/redact/xq_dark.webm" -> "branding/xq_dark.webm"
            else -> return null
        }
        val mime = when (name.substringAfterLast('.')) {
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "jpg" -> "image/jpeg"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
        return try {
            val stream = context.assets.open(name)
            val length = stream.available().toLong()
            // Chromium applies the request's Range header to this full stream.
            // Do not skip bytes or synthesize a second 206 response here.
            val headers = mapOf(
                "Cache-Control" to "no-cache",
                "Access-Control-Allow-Origin" to "*",
                "Accept-Ranges" to "bytes",
                "Content-Length" to length.toString(),
            )
            WebResourceResponse(
                mime,
                if (mime == "image/svg+xml") "UTF-8" else null,
                200,
                "OK",
                headers,
                if (request.method == "HEAD") {
                    stream.close()
                    java.io.ByteArrayInputStream(byteArrayOf())
                } else {
                    stream
                },
            )
        } catch (_: java.io.IOException) {
            null
        }
    }

    private val webView: WebView? = status.pageUrl?.takeIf { it.isNotBlank() }?.let { url ->
        createWebView(context, url)
    }
    private val errorView: TextView?
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    fun handleFileChooserResult(resultCode: Int, data: Intent?, preparedUris: Array<Uri>? = null) {
        val callback = filePathCallback ?: return
        filePathCallback = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }
        if (preparedUris != null) {
            callback.onReceiveValue(preparedUris)
            return
        }
        val files = mutableListOf<Uri>()
        data.clipData?.let { clips ->
            for (index in 0 until clips.itemCount) files += clips.getItemAt(index).uri
        }
        data.data?.let { files += it }
        callback.onReceiveValue(if (files.isEmpty()) null else files.toTypedArray())
    }

    private inner class BackupBridge {
        @JavascriptInterface
        fun shareBackup(rawPath: String): Boolean {
            val file = resolveBackupExport(File(context.filesDir, "magpie/home"), rawPath)
            if (file == null) {
                post { android.widget.Toast.makeText(context, R.string.backup_file_missing, android.widget.Toast.LENGTH_SHORT).show() }
                return false
            }
            BackupSharer.share(context, file)
            return true
        }
    }

    init {
        setBackgroundColor(Color.WHITE)

        if (webView != null) {
            addView(
                webView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            errorView = null
        } else {
            errorView = TextView(context).apply {
                text = context.getString(com.magpie.android.R.string.page_url_missing)
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            }
            addView(errorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun reload() {
        webView?.reload()
    }

    fun openSettings() {
        webView?.loadUrl("http://127.0.0.1:3430/?view=settings")
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() = webView?.goBack()

    private var destroyed = false

    override fun onDetachedFromWindow() {
        // 后台保留同一容器；停止/重启/错误移除时释放原生页面和轮询资源。
        if (!destroyed) {
            destroyed = true
            webView?.destroy()
        }
        super.onDetachedFromWindow()
    }

    /**
     * 补两条窄屏适配：只动被挤坏的那一处，不改整页布局宽度。
     *
     * ## 问题
     *
     * 供应商编辑弹窗的标题行 `.ehead` 是个 flex 行，成员是
     * 图标 + 名称 + "Token Plan · 按量付费" + 弹性占位 + 站外链接 + 开关。
     * 在 369dp 宽的屏幕上实测：站外链接固定吃掉 166px，弹性占位被压到 0，
     * 中间的说明只剩 **32px 宽、折成 6 行**，整个标题区高度 85px。
     *
     * ## 为什么不用「加宽整页」
     *
     * 试过把 viewport 的 width 提到 480，那一行确实恢复正常（98×34），
     * 但**整页随之变宽**，所有元素都跟着变，反而更难看，还需要缩小才装得下。
     * 为一个标题行改动全局布局，代价和观感都不划算。
     *
     * ## 实际做法
     *
     * 做法是把这一行的成员改成「按自身内容宽度、不换行」，并把站点链接的文字
     * 换成「官网」。实测 369dp 原生宽度下：标题区 85px → 48px，
     * 名称 88×20 单行、说明 120×17 单行、链接 32px，总宽 292 < 容器 353。
     * 整页宽度不变，其它页面不受影响。
     *
     * 链接换成短标签会丢掉"具体指向哪个域名"的信息，所以原域名仍写进 `title`，
     * 需要时能看到。
     *
     * 另外两处：
     *
     * - `.segs`（「区域」「模型」那两组选项）：成员宽度合计超过容器宽度，
     *   最后一个选项被折到第二行。改成整组不换行、但**选项内的文字允许折成两行**
     *   并居中。实测四组选项全部回到单行排列，高度 60 → 32：
     *   `套餐·中/国`、`套餐·新加/坡`、`套餐·欧/洲`、`按量付/费`。
     *
     *   早先试过 `overflow-x:auto` 做横向滚动，但只设 x 轴会让 y 轴的 `visible`
     *   一并变成 `auto`，容器因此出现纵向滚动条，看起来像页面在上下滚。
     * - `.bar`（供应商编辑弹窗底部操作栏）：「保存」被挤成 44×44、两个字竖排。
     *   收掉弹性占位 `.grow` 后按钮按内容宽度排布，整栏高度 93 → 48。
     *
     * - `.pm-manual`（插件页的手动安装区）：标签、输入框、按钮三者错位，
     *   输入框的提示文字需要 527px 而框只有 232px，被切掉大半。
     *   改成两行网格后输入框加宽到 260px，按钮与它同高对齐。
     *
     * - `.rt-accts`（路由页「还没有请求」栏）：它是 `<ol>`，浏览器默认的
     *   `padding-left: 22px` 没被重置，整栏比上方卡片右移 22px 显歪。
     *   magpie 已设 `list-style: none`，不需要这个缩进。
     *
     * - 顶栏的「更新」按钮（`.update-wrap`）隐藏：它跳的是 GitHub 发布页，
     *   而 App 里的 magpie 版本随 APK 打包，用户在那里更新不了。
     *
     * - 输入框字号抬到 17px：magpie 用 16px 字配 44px 的最小高度（移动端可点尺寸，
     *   不该缩小），字明显偏小。只改字号不动高度——`min-height: 44px` 会把
     *   height 顶回来，改了也没用。
     *
     * - 输入框里的长提示（如「npm 包名、GitHub 仓库（github:owner/repo）或本地文件夹」）
     *   原本被 `text-overflow:ellipsis` 截断。改成 clip，并让容器可收缩，
     *   提示改由原生行为滚动查看，不再丢字。
     *
     * - `.pm-registry`（「npm 源 https://registry.npmjs.org npm 官方源 修改」）：
     *   前三段占 307px，弹性占位 `.grow` 被挤到第二行，末尾的「修改」按钮
     *   只分到 44px，两个字竖排并被截成「修」。收起 `.grow` 后按钮拿到自然宽度。
     */
    private fun applyNarrowScreenFixes(view: WebView) {
        val js = """
            (function () {
              if (document.getElementById('magpie-android-tune')) return;
              var css = document.createElement('style');
              css.id = 'magpie-android-tune';
              css.textContent = [
                '.ehead > *:not(.grow){flex:0 0 auto;white-space:nowrap}',
                '.segs{flex-wrap:wrap !important;overflow:visible !important;',
                'column-gap:4px !important;row-gap:6px !important}',
                '.segs > *:not(.thumb){flex:0 1 auto !important;min-width:min-content !important;',
                'white-space:normal !important;line-height:1.25 !important;text-align:center}',
                '.rt-pool .ctl > .lab > .segs{width:100% !important}',
                '.rt-pool .ctl > .lab > .segs > *:not(.thumb){',
                'flex:0 0 calc(33.333% - 3px) !important;max-width:calc(33.333% - 3px) !important;',
                'min-width:0 !important;height:auto !important;min-height:32px !important;',
                'display:flex !important;align-items:center !important;justify-content:center !important}',
                // 底部操作栏：「保存」原本被压成 44x44，两个字竖排。
                // 收掉弹性占位、让按钮按内容宽度排，按钮内的长文字允许折成两行。
                '.bar{flex-wrap:nowrap !important}',
                '.bar > .grow{display:none !important}',
                '.bar > button,.bar > .btn{flex:0 0 auto !important;',
                'white-space:normal !important;text-align:center;',
                'line-height:1.25 !important;max-width:120px;height:auto !important;min-height:44px}',
                // 插件页手动安装区：标签、输入框、安装按钮原本错位，
                // 输入框的 placeholder 需要 527px 却只有 232px，提示被切掉大半。
                // 改成网格：标签独占一行，输入框与按钮同一行且高度对齐。
                '.pm-manual{display:grid !important;grid-template-columns:1fr auto !important;',
                'gap:8px !important;align-items:center !important}',
                '.pm-manual > .pm-mlabel{grid-column:1 / -1 !important}',
                '.pm-manual > input{grid-column:1 !important;width:100% !important;min-width:0 !important}',
                '.pm-manual > button{grid-column:2 !important;white-space:normal !important;',
                'text-align:center;line-height:1.2 !important;height:auto !important;',
                'min-height:44px !important;max-width:64px}',
                // 兜底：不让任何文字被省略号截断或溢出容器。
                // magpie 多处给窄容器设了 text-overflow:ellipsis，
                // 在手机上会把「npm 包名、GitHub 仓库（github:owner/repo）…」
                // 这类长提示直接切掉。允许折行比截断更可读。
                // 「npm 源 https://registry.npmjs.org npm 官方源」这一行
                // 三个片段挤在 353px 里放不下，末尾被截成单个「修」字。
                // 让它可以换行，各片段不互相挤压。
                '.pm-registry{flex-wrap:wrap !important;align-items:center !important;',
                'column-gap:8px !important;row-gap:6px !important}',

                // 路由页「还没有请求」那一栏是 <ol>，浏览器给列表默认的
                // padding-left: 22px 没有被 magpie 重置（它已设 list-style: none，
                // 并不需要留序号位置），整栏因此比上方的卡片右移 22px，看起来是歪的。
                // 清掉后与卡片左边缘齐平，栏宽 282 → 304。
                'body{overflow-x:hidden !important;max-width:100vw !important}',
                '#view-usage,#view-usage .card,#view-usage .list,#view-usage .row,#view-usage .quota{',
                'min-width:0 !important;max-width:100% !important;box-sizing:border-box !important;overflow-wrap:anywhere !important}',
                '.rt-accts{padding-left:0 !important}',
                '.view-ends{left:8px !important;right:auto !important}',
                '.auto-backup .val{flex:1 1 auto !important;min-width:0 !important}',
                '.auto-backup .val .segs{display:flex !important;flex-wrap:nowrap !important;',
                'width:100% !important;max-width:100% !important;box-sizing:border-box !important;',
                'column-gap:4px !important;row-gap:6px !important}',
                '.auto-backup .val .segs > *:not(.thumb){flex:1 1 18% !important;',
                'min-width:0 !important;white-space:nowrap !important;text-align:center !important;font-size:12px !important}',
                '.update-wrap,.update-pill-row,.update-auto-row,.update-every-row,',
                '.update-download-row,.update-restart-row{display:none !important}',
                // Local portrait, bird silhouette and demo videos. No UI rewrite.
                '.brand .logo>svg:not(.magpie-pixel-logo){display:none}',
                '.brand .magpie-pixel-logo{width:40px;height:44px;object-fit:contain;',
                'image-rendering:pixelated;flex:0 0 auto;cursor:pointer;',
                'transform-origin:50% 70%;transition:transform .16s ease-out}',
                '.brand .magpie-pixel-logo:hover{transform:translateY(-2px) rotate(-3deg)}',
                '.magpie-inline-logo{display:block;object-fit:contain;image-rendering:pixelated}',
                '.rt-hub .magpie-inline-logo{width:64px !important;height:52px !important;',
                'max-width:64px !important;max-height:52px !important}',
                '.sk-bird .magpie-skeleton-logo{position:absolute;left:-18px;top:-25px;',
                'width:36px;height:36px;object-fit:contain;image-rendering:pixelated}',
                '.xq-her{left:290px !important;width:440px !important;height:293.33px !important;top:16px !important}',
                '.xq-her video{object-fit:fill !important;-webkit-mask-image:none !important}',
                '.md-thumb{background:#e8f1fb url("/icons/mascot-head.png") center / 34px 34px no-repeat !important}',
                '#connect > *:not(.magpie-dsh-connect),#foldModels,#gwModels,#findModel,#copyModels{display:none !important}',
                'nav button:hover,nav button:focus{box-shadow:none !important;outline:none !important;background:transparent !important}',
                'nav button[data-view="agents"],nav button[data-view="sessions"],nav button[data-view="library"],',
                '#agents,#sessions,#library{display:none !important}',
                '.magpie-dsh-connect{display:block !important;width:100% !important;',
                'min-height:44px !important;margin:0 0 8px !important;text-align:center !important}',

                // magpie 的「更新」按钮点了跳 GitHub 发布页，但 App 里的 magpie
                // 版本是随 APK 打包的，用户在那里更新不了——点了没意义，隐藏掉。
                '.update-wrap{display:none !important}',
                // .grow 是弹性占位，在窄屏上会把「修改」按钮挤到下一行、
                // 并且只分到 44px，导致两个字竖排、末尾被截成单个「修」。
                '.pm-registry > .grow{display:none !important}',
                '.pm-registry > button,.pm-registry > .btn{flex:0 0 auto !important;',
                'white-space:normal !important;text-align:center !important;',
                'line-height:1.2 !important;height:auto !important;min-height:44px !important}',
                '.pm-registry > .pm-reg-url{min-width:0 !important;word-break:break-all !important}',
                '.pm-manual,.pm-manual *{min-width:0 !important}',
                '.pm-manual > input{text-overflow:clip !important}',
                'input[placeholder]{text-overflow:clip !important}',
                'input[type=text],input:not([type]),input[type=password],',
                'input[type=number],textarea,select{font-size:17px !important}'
              ].join('');
              document.head.appendChild(css);

              // Replace every use of the upstream bird, including route cards
              // and the roaming skeleton bird added after page load.
              var replaceMagpieWord = function (root) {
                if (!root) return;
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                var node;
                while ((node = walker.nextNode())) {
                  if (node.nodeValue.trim().toLowerCase() === 'magpie') node.nodeValue = 'Magpie Go';
                  if (node.nodeValue.includes('小鹊')) node.nodeValue = node.nodeValue.replace(/小鹊/g, '派卡');
                }
              };
              var installBranding = function () {
                document.title = 'Magpie Go';
                var brand = document.querySelector('.brand');
                replaceMagpieWord(brand);
                replaceMagpieWord(document.getElementById('mdRow'));
                replaceMagpieWord(document.querySelector('.xqd'));
                ['agents', 'sessions', 'library'].forEach(function (viewName) {
                  document.querySelector('nav button[data-view="' + viewName + '"]')
                    ?.style.setProperty('display', 'none', 'important');
                  document.getElementById(viewName)?.style.setProperty('display', 'none', 'important');
                });
                document.querySelectorAll('#view-usage button').forEach(function (button) {
                  if (button.textContent.trim() === '会话') button.style.setProperty('display', 'none', 'important');
                });
                document.querySelectorAll('[id^="codex"],[id^="warm"],[id^="claudeWarm"],'
                  + '#wslAgentsRow,#sessionTerminalRow,#agentPrefRows,#sessionReadsList,'
                  + '#usageAgents,#usageAgentsHid,#agentsInstall,#agentsManage,#view-agents,#view-sessions')
                  .forEach(function (el) { el.style.setProperty('display', 'none', 'important'); });
                var activeTab = document.querySelector('nav button.on');
                var navThumb = document.querySelector('nav .thumb');
                if (activeTab && navThumb) {
                  var nav = activeTab.closest('nav');
                  navThumb.style.left = '0px';
                  navThumb.style.right = 'auto';
                  var currentTransform = new DOMMatrix(getComputedStyle(navThumb).transform).m41;
                  navThumb.style.transform = 'translateX(' + (currentTransform + activeTab.getBoundingClientRect().left - navThumb.getBoundingClientRect().left) + 'px)';
                }
                var logo = document.querySelector('.brand .logo');
                if (logo && !logo.querySelector('.magpie-pixel-logo')) {
                  var bird = document.createElement('img');
                  bird.className = 'magpie-pixel-logo';
                  bird.alt = 'Magpie Go';
                  bird.src = 'icons/magpie-jump.png';
                  bird.addEventListener('pointerdown', function () {
                    bird.animate([
                      {transform:'translateY(0) rotate(0)'},
                      {transform:'translateY(-5px) rotate(-7deg)',offset:.35},
                      {transform:'translateY(0) rotate(4deg)',offset:.7},
                      {transform:'translateY(0) rotate(0)'}
                    ], {duration:700,easing:'steps(5,end)'});
                  });
                  logo.appendChild(bird);
                }
                document.querySelectorAll('svg g.bird').forEach(function (group) {
                  var image = document.createElementNS('http://www.w3.org/2000/svg', 'image');
                  image.setAttribute('href', '/plugins/redact/pica_wave.png');
                  image.setAttribute('x', '0');
                  image.setAttribute('y', '0');
                  image.setAttribute('width', '44');
                  image.setAttribute('height', '44');
                  image.setAttribute('preserveAspectRatio', 'xMidYMid meet');
                  image.setAttribute('class', 'bird');
                  group.replaceWith(image);
                });
                document.querySelectorAll('svg').forEach(function (svg) {
                  if (svg.closest('.brand .logo') || !svg.querySelector('use[href="#bird"]')) return;
                  var img = document.createElement('img');
                  var roaming = !!svg.closest('.sk-bird');
                  img.className = roaming ? 'magpie-skeleton-logo' : 'magpie-inline-logo';
                  if (svg.className.baseVal) img.className += ' ' + svg.className.baseVal;
                  img.alt = 'Magpie Go';
                  img.src = roaming ? 'icons/magpie-jump.png' : 'icons/magpie-smile.png';
                  svg.replaceWith(img);
                });
                document.querySelectorAll('.rt-hub').forEach(replaceMagpieWord);
              };
              installBranding();

              var disableMagpieUpdates = function (attempt) {
                if (window.__magpieGoUpdateDisabled || attempt > 20) return;
                if (typeof savePrefs !== 'function' || typeof prefs === 'undefined' || !prefs) {
                  setTimeout(function () { disableMagpieUpdates(attempt + 1); }, 250);
                  return;
                }
                window.__magpieGoUpdateDisabled = true;
                savePrefs({ noAutoUpdate: true, noUpdatePill: true });
              };
              disableMagpieUpdates(0);

              var installBackupSharing = function () {
                document.querySelectorAll('.sync-form .done').forEach(function (done) {
                  if (done.dataset.magpieBackupBound) return;
                  var text = done.textContent.trim();
                  var match = /^(?:Saved to|已保存到|已保存至|已儲存到|Gespeichert unter)\s*(.+)$/i.exec(text);
                  var path = match ? match[1].trim() : (text.endsWith(' に保存しました') ? text.slice(0, -' に保存しました'.length).trim() : null);
                  if (!path) return;
                  done.dataset.magpieBackupBound = '1';
                  var button = document.createElement('button');
                  button.type = 'button';
                  button.className = 'text primary';
                  button.textContent = document.documentElement.lang.startsWith('zh') ? '分享导出文件' : 'Share backup';
                  button.onclick = function () { window.MagpieGoBackup.shareBackup(path); };
                  done.appendChild(button);
                });
              };
              installBackupSharing();

              var installDshGoSetup = function () {
                var connect = document.getElementById('connect');
                var stack = connect?.querySelector('.stack');
                if (!stack || connect.querySelector('.magpie-dsh-connect')) return;
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'text primary magpie-dsh-connect';
                button.textContent = document.documentElement.lang.startsWith('zh')
                  ? '一键配置 DSH Go' : 'Set up DSH Go';
                button.onclick = function () { location.href = 'dshgo://providers/magpie'; };
                stack.style.display = 'none';
                connect.insertBefore(button, connect.firstChild);
              };
              installDshGoSetup();

              // 供应商编辑弹窗标题行的站点链接原本直接显示完整域名
              // （如 platform.xiaomimimo.com），在小屏上会把整行撑爆。
              // 统一显示为「官网」，跳转地址保持不变。
              var relabel = function () {
                document.querySelectorAll('.ehead .link.site').forEach(function (a) {
                  if (a.dataset.magpieRelabeled) return;
                  if (a.title) { a.dataset.magpieRelabeled = '1'; return; }
                  a.dataset.magpieRelabeled = '1';
                  a.title = a.textContent.trim();   // 原文放进 title，长按/悬停仍可见
                  a.textContent = '官网';
                });
              };
              relabel();
              new MutationObserver(function () { relabel(); installBranding(); installBackupSharing(); installDshGoSetup(); })
                .observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun createWebView(context: Context, url: String): WebView {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)

        return WebView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addJavascriptInterface(BackupBridge(), "MagpieGoBackup")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            // 允许双指缩放。
            //
            // magpie 的页面按 device-width 布局，在这个宽度的手机上，供应商编辑
            // 弹窗的标题行（名称 + "Token Plan · 按量付费" + 站点链接 + 开关）
            // 会把中间那段文字挤成 34px 宽、折成 3 行。强行把布局宽度改宽可以解决，
            // 但整页会变得过宽、需要横向滚动——那不是用户想要的。
            // 保持它自己的布局，把缩放交给用户：看不出细节时自己捏一下。
            settings.setSupportZoom(true)
            // 关掉内置缩放控件。
            //
            // 打开 builtInZoomControls 后，这台设备仍然在右侧浮出上下两个箭头，
            // 盖住了页面右下角的「安装」按钮——displayZoomControls = false 没能压住它。
            // 双指缩放靠 setSupportZoom 就够，不依赖这两个箭头。
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 100
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            manager.setAcceptThirdPartyCookies(this, false)

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    filePath: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    val activity = context as? Activity ?: return false
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = filePath
                    val chooser = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    return try {
                        activity.startActivityForResult(
                            Intent.createChooser(chooser, context.getString(R.string.backup_choose)),
                            REQUEST_BACKUP_FILE,
                        )
                        true
                    } catch (_: Exception) {
                        filePathCallback = null
                        false
                    }
                }
            }

            webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = brandedAsset(request)
                ?: super.shouldInterceptRequest(view, request)

                /**
                 * 只有 loopback 页面留在 WebView 里。
                 *
                 * 外部网页与支持的应用链接交给系统处理：magpie 的页面里会有
                 * 「文档」「社区」这类外链，在 App 内部打开既没有地址栏也没法返回，
                 * 用户会困在里面。危险协议（file/content/javascript/data）直接拦掉。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean {
                    val raw = request.url.toString()
                    return when (WebNavigationPolicy.destination(raw)) {
                        WebLinkDestination.Internal -> false
                        WebLinkDestination.External -> {
                            openExternal(context, raw)
                            true
                        }
                        WebLinkDestination.Blocked -> true
                    }
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    applyNarrowScreenFixes(view)
                    // 把 key 换来的 Cookie 落盘，冷启动才能免登录
                    manager.flush()
                    super.onPageFinished(view, finishedUrl)
                }

                /**
                 * 渲染进程崩溃：必须在这里返回 true 并清理，否则 chromium 会认为
                 * 应用没有处理，整个 WebView 变成不可用状态。
                 */
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail,
                ): Boolean {
                    android.util.Log.w(
                        TAG,
                        "renderer gone crashed=${detail.didCrash()}",
                    )
                    runCatching {
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        view.destroy()
                    }
                    manager.flush()
                    onRendererGone()
                    // true 表示已自行处理，系统不再终止宿主进程
                    return true
                }
            }

            // 下载交给系统下载器，并去掉 URL 里的 web key
            setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
                openDownload(context, downloadUrl, contentDisposition, mimeType)
            }

            // 关键：必须等视图真正挂进窗口之后再 loadUrl。
            // 在构造阶段直接 loadUrl 时，WebView 的 chromium 层不接受这次加载。
            post {
                loadUrl(url)
            }
        }
    }

    companion object {
        const val REQUEST_BACKUP_FILE = 0x4D47
    }
}

private const val TAG = "MagpieWeb"

/** 把状态换算成"要不要显示 WebView 容器"。 */
internal fun MagpieStatus.showsWebContainer(): Boolean =
    phase == MagpiePhase.Running && !pageUrl.isNullOrBlank()

/** 顶部栏显示用的网关地址（去掉协议前缀，便于阅读）。 */
internal fun MagpieStatus.gatewayDisplayAddress(): String =
    gatewayUrl
        ?.removePrefix("http://")
        ?.removePrefix("https://")
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
        ?: "127.0.0.1:3425"

/** 去掉 URL 里的 web key，用于日志与下载命名。 */
internal fun withoutWebKey(rawUrl: String): String {
    val uri = android.net.Uri.parse(rawUrl)
    if (uri.getQueryParameter("k") == null) return rawUrl
    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames.forEach { name ->
        if (name != "k") {
            uri.getQueryParameters(name).forEach { value ->
                builder.appendQueryParameter(name, value)
            }
        }
    }
    return builder.build().toString()
}

/** 用系统应用打开外部链接；没有可用应用时给出提示。 */
private fun openExternal(context: Context, rawUrl: String) {
    try {
        val intent = if (rawUrl.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(rawUrl))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, R.string.no_external_app, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 把下载交给系统的 DownloadManager。
 *
 * 必须先去掉 `k` 查询参数：下载请求走的是系统进程，magpie 用 Cookie 认证，
 * 把 key 留在 URL 里会让它出现在下载记录、通知和文件名中。
 */
private fun openDownload(
    context: Context,
    rawUrl: String,
    contentDisposition: String?,
    mimeType: String?,
) {
    try {
        val safeUrl = withoutWebKey(rawUrl)
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(safeUrl)).apply {
            setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            setDestinationInExternalFilesDir(
                context,
                android.os.Environment.DIRECTORY_DOWNLOADS,
                fileNameFrom(contentDisposition, mimeType),
            )
            // Cookie 由 DownloadManager 自己取，这里只允许必要的访问
            addRequestHeader("Cookie", android.webkit.CookieManager.getInstance().getCookie(safeUrl) ?: "")
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(request)
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, R.string.download_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 从 Content-Disposition 取文件名；取不到就用带时间戳的默认名。 */
private fun fileNameFrom(contentDisposition: String?, mimeType: String?): String {
    val fromHeader = contentDisposition
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.replace("\"", "")
        ?.takeIf { name -> name.isNotBlank() && !name.contains('/') && !name.contains('\\') }
    if (fromHeader != null) return fromHeader
    val ext = when {
        mimeType?.contains("json") == true -> "json"
        mimeType?.contains("csv") == true -> "csv"
        mimeType?.contains("zip") == true -> "zip"
        else -> "txt"
    }
    return "magpie-${System.currentTimeMillis()}.$ext"
}
