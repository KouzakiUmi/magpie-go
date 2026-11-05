# DSH Go 接收端 PR 交接

DSH Go 是独立上游项目。本仓库不携带其完整源码，也不把接收端改动当作 Magpie Go 的内部实现。以下改动保存在独立干净上游源码目录 `dsh-Go-magpie-upstream` 的 main 分支，基于官方 `faf420f`，提交为：

- `a76fafbbcea14a270efa3fe72b8e6cb393f07202`：三协议 Provider 接入。
- `4f000a4`：`auto/...` 别名按 `owned_by` 转为供应商前缀并去重。
- `b8f0bf3`：Magpie Group 导入、供应商显示名、视觉/思考元数据、并发写入与超时处理。
- `20d4118`：过滤 DSH 不支持的 `video` 模态，保留 `text` 和 `image`。
- `f49b70f`：成功提示按实际写入路由统计模型数。
- `9d6dd59`：刷新 PRoot 运行时包版本与哈希。

这些提交具有本地 SSH 签名；尚未推送或创建 PR。该源码来自官方 `ccch1mneyyy/dsh-Go`，不包含原有工作区的其他修改。

## 文件

- `app/lib/links/magpie_links.dart`：确认框、三条 Provider 的创建/更新、逐条 revision、凭据保存和验证。
- `app/lib/state/magpie_models.dart`：模型元数据读取、协议分组、思考档位、compat 与输出上限保留。
- `app/lib/links/app_links.dart`：注册 `dshgo://providers/magpie`。
- `packages/dsh_client/lib/src/providers_api.dart`：保留 `streamIdleTimeoutMs`。
- `app/lib/l10n/parts/view_en.arb`、`view_zh.arb` 及生成文案。
- 对应模型与序列化测试。

## 验证

- `flutter analyze` 通过。
- `flutter test test/providers/magpie_models_test.dart` 通过。
- `dart test test/provider_stream_idle_test.dart` 通过。
- 真机确认后生成 Magpie / Responses / Anthropic 三条路由，重复接入不重复创建。
- `packages/dsh_client` 全量测试另有一项既有 heartbeat 失败，未计为全量通过。

PR 中应说明：Android 应用之间不能直接读取彼此私有文件，因此由 DSH Go 通过 Provider/ConfigEditor 自行落盘，而不是 Magpie Go 直接修改 `cordis.patch.yml`。
