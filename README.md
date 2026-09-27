# OPPO Watch 文本同步（手机 ↔ 手表 蓝牙直连）

在手机上长按选中文本（验证码、取件码…）→ 在选项框点「**发送至手表**」→ 打开手表上的「**文本同步**」app 查看；**左滑**某条文本可**置顶 / 删除**，点卡片可放大复制。

专为 **OPPO Watch SE**（ColorOS Watch 精简版 / Android 8.1 / armeabi-v7a / 无 GMS）打造：

- **不走云服务器**：手机与手表经典蓝牙 RFCOMM 直连，复用系统配对，无需重新配对、无需网络；
- **手表零后台**：按需同步——发送的内容暂存手机，手表打开 app 的瞬间连上并秒级同步，退出即停；
- **免 Android Studio**：纯 Java + 命令行工具链（ECJ + aapt + d8 + apksigner）离线构建，ADB 侧载安装。

## 功能

**手机端「发送至手表」**
- 任意 app 长按选中文本 → 文本选择菜单「发送至手表」（PROCESS_TEXT）；另有系统分享入口兜底；
- 常驻前台服务持有蓝牙监听（开机自启）；手表不在线时自动暂存，连上即同步；
- 历史列表与手表同款交互：左滑置顶/删除、点击复制；深色/浅色主题可选。

**手表端「文本同步」**
- 打开 app 自动连接手机（从已配对设备中查找，安全 RFCOMM 优先），断线 10 秒重试；
- 置顶优先排序；左滑单条露出「置顶 / 删除」；点卡片大字查看全文 + 复制；新文本到达短振动；
- 离线期间的删除/置顶存入待同步队列，重连后自动回放，不会丢失。

## 架构

```
手机 app（权威数据端：列表 + 暂存，前台服务持 RFCOMM 监听）
        ▲ 经典蓝牙 RFCOMM（固定 UUID，复用系统配对）
        │ 手表打开 app 时作为客户端连上来，退出即断
手表 app（缓存镜像 + 待同步操作队列，仅 app 打开期间运行）
```

- 手机是权威端：发送时手表在线立即推送，离线则留在列表里，下次连接随全量列表送达；
- 手表离线期间的操作先本地生效 + 入队，重连后回放、收到列表才算落账；
- 协议：RFCOMM 流上「4 字节大端长度 + UTF-8 JSON」，仅 hello / ops / list 三种消息，详见 [docs/text_sync_protocol.md](docs/text_sync_protocol.md)。

## 构建

无需 Gradle / Android Studio，只需 JDK 8+ 与 Python 3：

```bash
# 首次构建：下载离线工具链（android.jar / build-tools / ECJ，约 40MB）
cd _android_build
pip install requests && python _dl_tools.py

python build_apk.py --app _phone_app --name PhoneTextSync --min-sdk 26 --target-sdk 28
python build_apk.py --app _watch_app --name WatchTextSync --min-sdk 24 --target-sdk 27
# 产物: out/PhoneTextSync.apk  out/WatchTextSync.apk
```

## 安装

不想自己构建？直接到 [Releases](https://github.com/D1ff1culTT/oppo-watch-text-sync/releases) 下载两个 APK（手机端 + 手表端）。

```bash
# 手机（开启 USB 调试后）
adb install -r _android_build/out/PhoneTextSync.apk

# 手表：先开启开发者模式（设置 → 关于手表 → 连点「版本号」5 次 → 打开 USB 调试），再
adb install -r _android_build/out/WatchTextSync.apk
```

> OPPO Watch SE 须选 32 位兼容的纯 Java 应用（本项目即是无 native 库的纯 dex APK，天然兼容）。

## 使用

1. 手机打开「发送至手表」（首次允许通知权限，后台服务保持运行即可，发送时也会自动拉起）；
2. 任意 app **长按选中文字** → 点「发送至手表」（若 ColorOS 收纳进二级菜单，点「更多」找；或用 **分享 → 发送至手表**）；
3. 手表打开「文本同步」→ 状态条变「● 已连接手机」即同步完成；
4. 手表/手机列表均可 **左滑置顶/删除**；手表点卡片看大字全文，手机点卡片直接复制。

已知边界：
- 手表与手机需保持蓝牙连接（正常佩戴配对状态即是）；
- 离线时发送的内容会在手表下次打开 app 时自动送达；
- 微信聊天记录的长按是微信自己的菜单，可先复制再从手机 app 手动发送；
- ColorOS「内容传送门」无公开的第三方接入通道，故未出现在传送门应用列表中（分享入口不受影响）。

## 项目结构

```
├── _common/src/          # 两端共享的蓝牙同步协议层（Proto.java）
├── _phone_app/           # 手机端「发送至手表」（纯 Java，无第三方依赖）
├── _watch_app/           # 手表端「文本同步」（纯 Java，无第三方依赖）
├── _android_build/
│   ├── build_apk.py      # 离线构建: aapt打包 → ECJ编译 → d8转dex → zipalign → apksigner签名
│   ├── _dl_tools.py      # 首次构建前下载工具链
│   └── out/              # 构建产物（APK）
└── docs/
    └── text_sync_protocol.md
```

## License

MIT
