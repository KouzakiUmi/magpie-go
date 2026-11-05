# DSH Go 修改版构建交接

DSH Go 是独立上游项目。本仓库不携带其完整源码，也不把接收端改动当作 Magpie Go 的内部实现。以下改动保存在独立干净上游源码目录 `dsh-Go-magpie-upstream` 的 main 分支，已 rebase 到官方 `aa0daddb`，提交为：

- `ff3d687`：三协议 Provider 接入。
- `0460b60`：供应商模型 ID 处理。
- `9670424`：刷新 PRoot 运行时包版本与哈希。
- `83b00c5`：供应商分组、显示名、视觉/思考元数据、并发写入与超时处理。
- `a611ffd`：过滤 DSH 不支持的 `video` 模态，保留 `text` 和 `image`。
- `2c131c7`：成功提示按实际写入路由统计模型数。
- `8b93314`：保留 `auto/...` 自动路由 ID，独立为 Auto Routing，其余模型按供应商和协议分组。
- `424d3bf`：供应商 slug、稳定哈希与协议组成唯一路由 ID，旧 Group 清理校验归属。
- `cbfcd1d2`：当前上游整合；仅保留模型选择器与思考档位布局，补充 Overlay/搜索控制、旧供应商路由迁移与过期路由清理。
- `4b93cf9`：独立 Magpie 接收端的过期路由协调与迁移测试。

这些提交具有本地 SSH 签名。DSH Go 上游不接受 PR，因此不向官方提交 PR，改为提供基于当前上游的修改版 APK。该源码来自官方 `ccch1mneyyy/dsh-Go`，不包含原有工作区的其他修改。

## 文件

- `app/lib/links/magpie_links.dart`：确认框；按 API 的三条路由，以及按 Provider 的 Auto Routing/供应商/协议路由创建与更新、逐条 revision 和凭据保存。
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
