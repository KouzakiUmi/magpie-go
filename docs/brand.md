# 品牌说明：Magpie Go

Android 客户端的应用名为 **Magpie Go**，吉祥物名为 **派卡（Pica，取 Picker 意象）**，核心用途是适配 DSH Go。包名 `com.magpie.android` 保持不变，以免影响已安装版本与运行时接口。

## 名称

| 语言 | 名称 |
|---|---|
| 简体中文 | Magpie Go |
| English | Magpie Go |

启动器、Compose 首页、前台通知、WebView 左上角和路由卡片品牌名称统一。`app_name` 位于 `values/strings.xml` 与 `values-en/strings.xml`。

## 图标

- 启动器：浅色冰蓝背景、完整双翼的原创 Kemono 喜鹊半身像素图；主体位于自适应图标安全区内。
- 网页左上角：侧身全身跳跃图，与启动器区分，并保留点击动画。
- 路由中央卡片：笑脸角色大图和 `Magpie Go` 名称。
- 动态加载：Q 版全身侧身跳跃图，支持左右镜像。
- 脱敏入口：完整大头照；脱敏演示为 672×448 横版动作视频。
- 通知与单色层：使用同一角色轮廓，由系统着色。

原稿和派生方式见 `design/pixel-mascot/README.md`。角色、图标和动画 ARR（保留所有权利），不随代码的 CC BY-NC 许可单独授权复用。

不要拉伸角色、裁掉冠羽或双翼、叠加文字、使用旧官方鸟标，或将参考角色当作本项目角色。
