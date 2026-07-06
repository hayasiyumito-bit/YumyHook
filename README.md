# YumyHook

基于 LSPosed / Xposed 的 Android 系统层设备伪装模块。通过 Hook `android.os.Build`、`SystemProperties`、`getprop` 及 Native 属性读取，对作用域内 App 返回可配置的伪装参数，并附带多项反检测能力。

> **许可**：[CC BY-NC 4.0](LICENSE)（署名-非商业性使用）。第三方禁止商用；**版权人 Yumito 保留独占商用权**。详见 [COMMERCIAL_RIGHTS.md](COMMERCIAL_RIGHTS.md)。

谱系指纹：`YH-LIN-8d4e2f91-yumito`（源码 / APK / logcat 可检索，用于追溯未授权商用）

## 功能概览

- **四通道属性对齐**：Build 静态字段、Java `SystemProperties`、`getprop`、Native `__system_property_get`
- **多配置档案**：支持多 Tab 配置、分区保存 Build / SIM 参数
- **反检测**：隐藏 Root / LSPosed、过滤 `/proc/maps`、屏蔽 shell 探测等（部分为实验项）
- **稳定性**：配置变更后（开关 / 按 App 四通道 / 参数）有 Root 时自动强停作用域内 App；可按作用域 App 单独关闭四通道

## 环境要求

| 项 | 要求 |
|----|------|
| Android | 7.0+（minSdk 24） |
| 框架 | LSPosed（或兼容 Xposed API 54+ 的环境） |
| Root | 可选；用于强停作用域 App、读取 LSPosed 配置 |
| 构建 | Android Studio / JDK 11+ / Android SDK 37 |

## 安装

1. 克隆仓库并构建 Debug APK：

   ```bash
   git clone https://gitee.com/Yumito/yumy-hook.git
   cd yumy-hook
   ./gradlew assembleDebug
   ```

2. 安装 `app/build/outputs/apk/debug/app-debug.apk`
3. 在 LSPosed 中启用 **YumyHook**，勾选目标 App 作用域
4. 强停目标 App 后重新打开，或在模块内开启 Hook 总开关（需 Root 时会自动强停）

## 使用

1. 打开 YumyHook → 确认 Xposed 状态为已激活
2. 开启 **Hook 伪装** 总开关
3. **编辑 Hook 配置** → 调整功能开关与设备参数
4. 在目标 App（如 `com.android.device`）重新采集验证

调试日志（配置页开关）：

```bash
adb logcat -s YumyHook YumyHookConfig
```

## 项目结构

```
app/src/main/kotlin/com/yumito/yumyhook/
├── MainActivity.kt          # 主页
├── ui/                      # DataBinding + ViewModel UI
├── model/                   # 配置数据模型
├── util/                    # 持久化、Root、LSPosed 读取
└── xposed/                  # Hook 入口与实现
    ├── XposedEntry.kt
    └── hook/                # Build / getprop / stealth
```

Hook 入口：`app/src/main/assets/xposed_init`

## 开发

```bash
./gradlew assembleDebug   # 构建
./gradlew test            # 单元测试（如有）
```

仅 Kotlin 源码目录：`app/src/main/kotlin/`（禁止 `java/` 源集）

## 免责声明

本工具仅供学习与研究。使用者须遵守当地法律法规及目标应用的服务条款。作者不对滥用造成的任何后果负责。

## 仓库

- Gitee: https://gitee.com/Yumito/yumy-hook.git
