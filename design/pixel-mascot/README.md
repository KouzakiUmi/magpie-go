# 派卡（Pica）角色与应用资产

## 已确认的角色设定

吉祥物名为 **派卡（Pica，取 Picker 意象）**。用户已确认 `design/character-turnaround/kxyai-2ff44ba5.png` 的三视图方向。派卡是原创 Kemono 喜鹊：深蓝层叠冠羽、白色脸胸、短鸟喙、蓝宝石眼睛、黑白羽翼、钴蓝与青绿羽端、长尾羽及鸟足。不是官方小鹊、官方单色鸟标、Tulin 或像素参考图中的犬科角色；没有服装、围巾、肉垫或人类四肢。

精细设定稿先行，像素化只用于应用资产。保留原始生成图，勿覆盖。`design/icon-v02/` 中早期候选不是最终角色来源。

## 资产与生成脚本

- `kxyai-02fb0b12.png`：透明像素半身像原稿，用于启动器、网页角色和大头照。
- `jump-magpie.png`：Q 版全身侧身原稿，朝右跳跃、脚爪收起、长尾向后；左跳由应用水平镜像。
- `keyframes/key-00.png` 至 `key-26.png`：八张横版动作原稿。每张以对应原视频关键帧为构图与动作目标、以已确认角色替换人物，不包含原版人物。
- `build_assets.py`：生成启动器前景、单色轮廓及网页半身像。启动器前景限制在自适应图标安全区，双翼不得被遮罩裁切。
- `build_motion_assets.py`：生成 `magpie_jump` 全身跳跃图和 `mascot-head.png` 完整大头照。
- `build_videos.py`：把八段动作合成 **672×448、24fps、27.5 秒、VP9 WebM** 的浅色/深色连续动画，并生成对应海报与 `master/landscape-contact.png` 审阅图。
- `asset-manifest.json`：主要输入与派生资产哈希。

所有像素派生使用最近邻；深色视频只调整背景和桌面，不改变角色固有色。原始生成图与派生图分开保存。

## Android 接入

- 启动器使用浅底完整双翼角色和同轮廓单色层。
- Compose 加载页使用 `magpie_jump`，3 秒循环完成右跳、落地、转身、左跳、落地；ImageBitmap 使用 `FilterQuality.None`。
- `MagpieWebContainer.brandedAsset()` 只对本机 loopback 的固定 favicon、触控图标、大头照和脱敏演示路径返回 APK assets，其他请求照常交给 Magpie。
- 本地视频响应提供完整流、长度和 `Accept-Ranges: bytes`。Chromium 自行应用 Range；不要在应用层再次跳过字节或合成第二个 206。
- 网页左上角沿用原 `.brand .logo` 点击区域，使用 700ms 分步交互动画；演示入口使用完整大头照。
- 视频保留原中央画布、八步时间轴、单遍播放、按需加载、折叠释放源、减少动态效果与无视频回退。卡片、占位符、文案和控件仍由 Magpie 原页面管理。
- `libmagpie.so`、运行时和数据层未修改，不修改内嵌媒体或二进制哈希。

## 验收

三视图已经用户确认。脱敏后的设备实测记录见 `docs/verification.md`。设备截图仅保留在本机，不进入公开仓库。重新生成资产后，至少运行 `:app:assembleDebug`、`:app:testDebugUnitTest`，并在设备上复查启动器裁切、加载跳跃、网页点击、横版视频播放与进度拖动。
