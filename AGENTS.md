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
- **Hook**：Xposed API `compileOnly`，入口 `assets/xposed_init`
- **作用域**：LSPosed 作用域内**所有 App**（见 `.cursor/rules/hook-scope.mdc`）；**禁止**单包特化 Hook
- **参考验证**：`com.android.device` 仅对照采集方式 / 回归 `debug_output.json`，非 Hook 目标
- **测试**：仅 `src/test/kotlin/` 单元测试，无 androidTest

## 目录结构

```
app/src/main/kotlin/com/yumito/YumyHook/
├── MainActivity.kt
├── ui/main/
├── model/
├── util/
└── xposed/
    ├── XposedEntry.kt
    └── hook/
```

## Xposed 参考

https://api.xposed.info/reference/packages.html
