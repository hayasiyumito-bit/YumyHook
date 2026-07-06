# YumyHook — Agent Guide

## 常驻工作流（强制）

| 技能 | 路径 | 时机 |
|------|------|------|
| **Superpowers** | `.agents/skills/using-superpowers/` | 每任务开始前读；功能→brainstorming；实现→verification |
| **Caveman** | `.agents/skills/caveman/` | 用户回复默认极简风格 |

详见 `.cursor/rules/ai-workflow.mdc`（`alwaysApply: true`）

## 代码智能

| 工具 | 用途 | 用法 |
|------|------|------|
| **Codegraph** | 符号索引、调用链 | `codegraph explore` / MCP `codegraph_explore` |
| **Understand** | 架构知识图谱 | `.agents/skills/understand/`，运行 `/understand` |

索引：`.codegraph/`

## 技术栈

- **语言**：仅 Kotlin，目录 `src/**/kotlin/`（禁止 `java/`）
- **UI**：DataBinding + ViewModel（禁止 Compose）
- **Hook**：Xposed API `compileOnly`，入口 `assets/xposed_init` → `xposed.entry.XposedEntry`
- **作用域**：LSPosed 作用域内**所有 App**（见 `.cursor/rules/hook-scope.mdc`）；**禁止**单包特化 Hook
- **参考验证**：`com.android.device` 仅对照采集方式 / 回归 `debug_output.json`，非 Hook 目标
- **测试**：仅 `src/test/kotlin/` 单元测试，无 androidTest

## 目录结构

```
app/src/main/kotlin/com/yumito/yumyhook/
├── MainActivity.kt
├── ProjectAttribution.kt
├── model/                    # 纯数据 + HookFeatures 领域方法
├── data/
│   ├── profile/              # HookProfilesStore（唯一配置持久化入口）
│   ├── publish/              # Hook 侧配置发布（chmod / mirror）
│   └── lsposed/              # LSPosed 作用域、Root、状态检测
├── feature/
│   ├── home/                 # MainViewModel
│   ├── config/               # ConfigDebugLog
│   └── session/              # Hook 总开关 + 强停目标 App
├── ui/                       # Activity / Adapter / 布局绑定
└── xposed/
    ├── entry/                # XposedEntry
    ├── config/               # HookConfig、SpoofConfigFile、HookSpoofValues
    ├── channel/              # 四通道：Build / getprop / SystemProperties / Native
    ├── policy/               # FourChannelGate、HookScope
    ├── runtime/              # SpoofRuntime、TargetContextHolder、重入保护
    └── stealth/              # 反检测 Hook（按 HookFeatures 门控）
        ├── install/          # FeatureStealthInstaller、延迟安装
        ├── common/           # StealthConstants
        ├── hide/             # LSPosed / maps / 敏感路径 / 包隐藏
        ├── root/             # Root / ADB / 开发者选项 / Shell 探测
        ├── network/          # VPN / 代理 / 局域网扫描
        ├── airplane/         # 飞行模式
        ├── wifi/             # Wi-Fi
        ├── bluetooth/        # 蓝牙
        ├── location/         # 地理位置伪装
        ├── telephony/        # SIM / 设备标识
        ├── identity/         # 应用身份 / 安装来源
        └── device/           # 运行时长 / 浏览器指纹
```

### 约定

- **配置读写**：UI 只调 `HookProfilesStore`（已合并原 `HookPrefs` / `HookProfileStore`）
- **四通道门控**：运行时 `FourChannelGate`；纯规则 `FourChannelPolicy`
- **Native JNI**：`xposed.channel.NativeBridge`（改包名须同步 `native_bridge.cpp`）

## Xposed 参考

https://api.xposed.info/reference/packages.html
