# 当前需求清单与执行顺序

> 统一记录本轮新增要求。旧实测和维护边界仍以 `AGENTS.md`、`docs/verified-findings.md` 为准。
> 状态只区分：待办 / 进行中 / 已完成（有对应验证证据）。

## P0 — 配置导入与导出必须可用（已完成）

- 修复「设置 › 同步与备份 › 导出 / 导入」在 Android 上的完整流程。
- 导出不能只返回 PRoot 内部路径；需要让用户通过系统分享拿到 `.magpie-backup` 文件。
- 导入必须支持系统文件选择，并把文件内容交回 Magpie 原有导入逻辑，不重写备份格式。
- 不输出或上传 API key、web key、订阅登录态和配置正文。测试优先使用不含密钥的备份，真实恢复与文件选择分别记录。
- 诊断日志导出与配置导入/导出是不同功能；日志分享已实测可生成文件并弹出系统分享。

## P1 — DSH Go 一键配置 Provider（已完成）

- 复刻 Magpie 桌面端的 DSH 接入结果，而不是只展示手工填写说明。
- 已核实的桌面实现：`internal/agent/dsh.go` 向 `llm-pi-ai.providers` 合并 Magpie 路由、写入 `agent-default-model` 和 `MAGPIE_GATEWAY_KEY`，并保留用户已有 Provider 与断开还原信息。
- DSH Go 使用 web profile：`/root/.dsh/profiles/web/cordis.patch.yml`。Android 应用之间不能直接访问彼此私有文件，所以应通过 DSH Go 已有 Provider/ConfigEditor 接口完成同样的文件写入，不跨沙箱直接改文件。
- 使用 DSH Go 已有 `dshgo://` 链接入口和 `ProviderActions` / `llm/discoverModels` / `credentials.set`。
- 复用 `/v1/models` 发现结果；已实测当前 Magpie 返回 70 个模型，含上下文、模态和思考档位信息。
- 保留已有路由、模型能力、协议和 compat 字段；重复执行不重复建 Provider；失败不得留下无提示的半配置。
- 桌面源码研究见 `design/research/`。研究完成后形成明确实现方案，再分别修改 Magpie Go 的入口和 DSH Go 的接收端。

## P2 — 图标、当前布局与更新入口（已完成）

- 全面替换用户可见的旧鸟标：启动器、通知、WebView 左上角、路由页中央卡片、动态加载鸟、favicon、脱敏头像和两版演示。
- 修正「同步与备份 › 自动备份」检查间隔选项组：占满可用宽度，必要时换行，不能超出屏幕。
- 调整当前页右下角 Home/End 上下导航按钮的位置，避开插件安装按钮和系统工具栏。
- 左上角品牌文字统一为 **Magpie Go**。
- 同期核对窄屏 `.segs` 的截断问题，不能只凭截图判断是否溢出；模型选择界面的按钮也要检查并修正文字超出。
- 暂时停用 Magpie 自身自动更新和检查更新入口。
- 后续另立“APK 内更新 Magpie 内核”任务：仅使用官方 release，核验签名/哈希，写入 App 私有可执行目录并切换运行时；不得直接覆盖只读 `nativeLibraryDir`。

## P3 — 主题、About 与工具栏（已完成）

- 提供浅色和深色模式；浅色不用纯白，使用角色的冰蓝、暖白、深蓝、钴蓝和青绿配色。
- 工具栏：左侧菜单包含「导出日志」，中间「网络代理」，右侧「关于」。
- About 使用用户提供的 `umi-title.png` 作为作者头像。
- 首页和 About 加入图像生成的派卡原画帧动画：站立、Wink、挥翼和开心动作，参考 `pi-furry-partner` 的表现方式，不复用其他角色身份，不使用脚本修图。
- About 下方放作者介绍、GitHub、contributors 与感谢信息。
- 版权：代码 CC BY-NC；内置 Magpie 按上游 MIT；原创角色和动画 ARR（保留所有权利）。
- 作者 GitHub：`https://github.com/KouzakiUmi`。

## P4 — 文档与公开发布（已完成）

- 整理项目 README、版权、第三方声明、设计、验证记录和未完成事项，文档内容必须与最终实现一致。
- 所有计划内修改至少运行 `:app:assembleDebug`；涉及 Kotlin 逻辑运行 `:app:testDebugUnitTest`；关键流程真机验证。
- 新建公开 GitHub 仓库 `KouzakiUmi/magpie-go` 并推送。推送前完成身份、签名和独立只读交叉核验。
- DSH Go 的配套源码改动不随 Magpie Go 仓库隐式发布；其提交与推送需要单独核对。

## P5 — 当前供应商余额 / 用量通知（未实现，详见 `docs/unfinished-work.md`）

- 每个当前启用的供应商账户分别显示一条通知，不合并币种。
- 优先余额，其次额度窗口；无法读取时回退 token / 消费统计。
- 已实测 `/api/providers` 和 `/api/usage/quotas` 的数据结构，详见 `docs/pending-work.md`。

## P6 — APK / 内核更新模式（未实现，详见 `docs/unfinished-work.md`）

- 暂时移除更新检查和自动更新，不复用 Magpie 桌面安装器的更新方式。
- 后续提供受控的内核更新机制，验证下载、签名、哈希、切换、回滚和真实启动。
