# DSH Go 一键 Provider 接入设计

## 目标

让 Magpie Go 的网关页通过一次确认，把本机 Magpie 的模型接入 DSH Go，并达到桌面端 Agent 接入的持久化结果：Provider、模型、协议、凭据和默认路由在重启后仍可使用。

## 桌面端依据

上游 `yetone/magpie` 的 `internal/agent/dsh.go` 已核实：

- `llm-pi-ai.providers` 中合并 Magpie 路由，不替换用户已有 Provider。
- 按原生协议分成 `magpie`、`magpie-responses`、`magpie-anthropic`。
- 使用 `MAGPIE_GATEWAY_KEY`；OpenAI 路由使用 `/v1`，Anthropic 使用网关根路径。
- 模型携带上下文、输出上限、输入模态、思考档位和 compat；流式等待为 600000ms。
- 保留用户已有 compat 与输出上限，断开时依据 stash 还原。

本地研究副本位于 `design/research/upstream-agent-dsh.go`，另含桌面 profile、home patch、凭据存储和测试文件。

## Android 数据流

1. Magpie Go 网关页显示「一键配置 DSH Go」。
2. 系统打开 `dshgo://providers/magpie`，DSH Go 的 `LinkTrampolineActivity` 与 `dsh/links` 交付给 `LinkRouter`。
3. DSH Go 显示确认框；用户确认前不修改配置。
4. DSH Go 调用 `ProviderActions.discover` 验证引擎能读取模型，然后补读 Magpie `/v1/models` 的完整元数据。
5. DSH Go 通过 `ProviderBackend.create/update` 写入 web profile 的 `cordis.patch.yml`，通过 `credentials.set` 保存 `MAGPIE_GATEWAY_KEY`。
6. 按 API 导入时更新三条协议路由；重复接入不重复创建，空协议路由仅在按 API 导入时移除。按 Provider 导入时分别写入 Auto Routing 和供应商路由。

### 三条路由

| 路由 | 协议 | Base URL | 模型来源 |
|---|---|---|---|
| `magpie` | `openai-completions` | `http://127.0.0.1:3425/v1` | 没有 Responses 或 Messages 原生端点的模型 |
| `magpie-responses` | `openai-responses` | `http://127.0.0.1:3425/v1` | `native_endpoints` 含 `/v1/responses` |
| `magpie-anthropic` | `anthropic-messages` | `http://127.0.0.1:3425` | 不含 Responses、但含 `/v1/messages` |

响应式协议优先于 Anthropic，与桌面 `dshRouteOf` 一致。

## 为什么不跨 App 直接写文件

DSH Go 的 `/root/.dsh` 位于它自己的私有运行时目录，Magpie Go 无权直接读写。接入由 DSH 自己通过 Provider/ConfigEditor 完成相同的文件落盘，不共享 DSH 的 token、Cookie 或私有配置正文。

Magpie Go 只发起明确的接入链接，不接收 DSH Go 的凭据。

## 保留与兼容

- `ProviderProfile.streamIdleTimeoutMs` 在客户端读写中保留，新增路由设为 600000ms。
- 同一协议、同一模型保留 compat 和用户设置的 `maxTokens`；协议变化时重新计算。
- 输出上限按桌面规则为压缩预留空间：至少保留窗口四分之一，或窗口一半减 65536 中的较大者。
- `reasoningEfforts` 使用 Magpie 当前 `supported_reasoning_levels` / `reasoning_levels`。
- `auto/...` 保留自动路由原 ID，不与供应商模型合并；普通供应商路由使用 slug、稳定哈希和协议共同构成唯一路由 ID。
- 只更新当前导入模式管理的 Magpie 路由，其他 Provider 不动；旧 Magpie Group 仅在 displayName 确认属于旧导入器时清理。

## 验证

- Magpie Go：导航策略、备份文件映射、构建与真机链接。
- DSH Go：三协议分组、compat 与输出上限保留、思考档位、深链接校验、`ProviderProfile` 超时字段往返。
- 真机：确认框 → 三协议 Provider 出现 → 重复接入不重复 → 模型可选 → 重启后配置仍可读。
- 当前 Magpie `/v1/models` 已用于分组验证；模型数随供应商配置变化，Responses 与 Messages 使用 `native_endpoints` 识别。

## 导入方式与同步

- 按 API 导入使用 `magpie`、`magpie-responses`、`magpie-anthropic` 三条协议路由。
- 按 Provider 导入按 `owned_by` 分供应商，并按 DSH 要求拆成各协议路由；`auto/...` 自动路由模型单独进入 `Auto Routing` 及其 Responses/Anthropic 路由。
- `auto/...` 保留原 ID，不改写为供应商 ID；普通模型 displayName 带供应商，自动路由模型带 `Auto Routing` 前缀。
- 视觉模态只保留 DSH 支持的 `text` 和 `image`，过滤 `video`；Reasoning 使用 `off/light/xhigh/ultra` 映射。
- 只允许本机引擎接入；更新每条路由前重新读取该路由和 revision，创建也带 revision，避免覆盖并发修改。
- 手机端每分钟比较模型列表指纹；变化时通知“列表发生改变，请重新导入更新”，不自动覆盖现有配置。
