# Magpie Go v0.1.0 + DSH Go 修改版

本 Release 包含两个 Android arm64 APK：

1. **Magpie Go**：本机 Magpie 运行时、管理页、备份、主题、派卡动画和可选 Bun 插件运行时。
2. **DSH Go 修改版**：包含 Magpie Provider 接入、Auto Routing 和模型选择器调整。DSH Go 上游不接受 PR，因此以修改版 APK 提供，不向官方提交 PR。

## 自动导入与手动配置的区别

### 自动导入

使用「一键配置 DSH Go」时，**必须安装本 Release 中的 DSH Go 修改版 APK**。

- 按 API 导入：创建 `magpie`、`magpie-responses`、`magpie-anthropic` 三条协议路由。
- 按 Provider 导入：按供应商分组，并将 `auto/...` 自动路由模型放入 **Auto Routing**。
- 修改版 DSH Go 的接收端已包含这些导入逻辑；官方 DSH Go 不包含本接收端。

### 手动配置

**不需要修改版 DSH Go。** 官方 DSH Go 或其他兼容客户端都可以按下面方式手动添加 Provider。

先安装并启动 Magpie Go，完成供应商登录和模型发现，然后在 DSH Go 的「设置 → 模型 / 自定义 Provider」中添加：

| 用途 | 协议 | Base URL |
|---|---|---|
| OpenAI Chat Completions | `openai-completions` | `http://127.0.0.1:3425/v1` |
| OpenAI Responses | `openai-responses` | `http://127.0.0.1:3425/v1` |
| Anthropic Messages | `anthropic-messages` | `http://127.0.0.1:3425` |

- API Key：`magpie`（本机固定互联值，不是个人 API Key）。
- 模型列表使用 Magpie 的发现接口：`http://127.0.0.1:3425/v1/models`。
- `auto/...` 模型保留原 ID，可作为 Magpie 的自动路由使用；不要改成供应商 ID。
- 如需按供应商区分，可手动创建多个 Provider，分别填写对应模型。
- 流式空闲超时建议设置为 `600000 ms`。
- 网关仅监听本机 `127.0.0.1`，配置和登录状态保留在各自应用私有目录。

如果只需要手动配置，可以不安装修改版 DSH Go；如果需要自动导入，请同时安装两个 APK。

## 安装提示

两个 APK 都是测试构建。若系统提示签名冲突，请先备份配置，不要直接卸载正在使用的 App。修改版 DSH Go 的签名与官方包不同，安装前请确认测试设备和数据备份。

## SHA-256

- `magpie-go-v0.1.0-arm64-debug.apk`：`2d899dcb4bb0008e13d1a359939beb79a5527983909df55f0e2f419b36cca851`
- `dsh-go-magpie-v1.0.11-arm64-release.apk`：`beb2cdba417e3fd9e712341041ea30eac372c97f84f1f55242b71d57049d4bf7`
