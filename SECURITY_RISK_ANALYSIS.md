# 安全风险判定分析报告

> 基于 `debug_output.json`（采集时间戳 `2026-07-07_095308+0000`）与 `deviceinfo_collect` 当前代码逻辑整理。  
> 设备型号：`2201123G`（Xiaomi / cupid，Android 14）。  
> 用途：交还 `com.android.device` 同步检测逻辑；同时记录 **Magisk / KernelSU / APatch / 系统 su** 四类框架的通用检测面（其他业务 App 已证明同类向量仍会命中）。

---

## 1. 结论摘要

| 项目 | 结果 |
|------|------|
| `anyRisk` | **true** |
| 主要触发原因 | **Native 探测命中 Magisk/Zygisk**；**Native 路径可访问 `/debug_ramdisk/magisk`** |
| `hideSuspected` / `magiskHideSuspected` | **false**（device §7 误判修复已生效） |
| `frameworkConfirmed` | **true**（`magiskDetected=true`，Native 归因） |
| `isRooted` | **true**（`frameworkConfirmed`，非疑似隐藏单独触发） |
| Hook 框架 | 未检出 |
| 属性篡改 | 未检出 |
| su 授权 | 未获取（DenyList 生效） |
| Java 层框架路径 | 全部不可见（Hide 生效） |
| **Native JNI 层** | **仍暴露 Magisk / Zygisk 痕迹** |

**判定结论：合理。** 新版逻辑已不再因 Boot 解锁或单纯 `hideSuspected` 误报 Root；当前剩余风险来自 **Native `getMagiskNativeProbe` 未被 YumyHook 完全对齐**。

---

## 2. 判定链路（当前代码）

```
DeviceSnapshotMerger.collectFresh()
  └─ SecurityReportComposer.build(context)
       ├─ CheckEmu.buildHookSection()      → hook / propertyProbes
       └─ CheckEmu.buildRootSection()      → RootFrameworkDetector.probe()
            └─ summary.anyRisk 聚合
```

### 2.1 `isRooted`（`CheckEmu.buildRootSection`）

```
frameworkConfirmed = magiskDetected || kernelsuDetected || apatchDetected || systemSuDetected
isRooted = hasPositiveRootIndicator(indicators) || frameworkConfirmed
```

- **`hideSuspected` 不再单独使 `isRooted=true`**（§7 已修复）。
- `magiskHideSuspected` 为 `hideSuspected` 的兼容字段名。

### 2.2 `rootProbe.detected`（`RootFrameworkDetector.probe`）

```
frameworkConfirmed = 四类框架任一 detected
hideSuspected = (maps/mount 命中 || native 命中 || javaNativeMismatches)
                && Java 路径全不可见 && su 未授权
                && !frameworkConfirmed   // 已确认则不再标疑似
detected = frameworkConfirmed || hideSuspected
```

### 2.3 Native 归因（`applyNativeProbeToFrameworks`）

`probe()` 在 Java 框架扫描后，用 `JniInterface.getMagiskNativeProbe()` 结果 **回写** 各框架 `detected`：

| Native 信号 | 归因框架 |
|-------------|----------|
| maps/mount 含 `magisk`/`zygisk`/`magiskpolicy`/`resetprop` | Magisk |
| maps/mount 含 `kernelsu`/`ksud`/`kernel_su`，或 maps **精确** `ksu` | KernelSU |
| maps/mount 含 `apatch`/`bmax` | APatch |
| `accessiblePaths` 路径含 `magisk` / `ksu` / `kernelsu` / `/ap` / `apd` | 对应框架 |

因此：**即使 Java `probeFramework` 四类均为 false，Native 单独命中也可使 `magiskDetected=true` 并拉高 `isRooted`。**

### 2.4 `anyRisk`（`SecurityReportComposer.buildSummary`）

以下任一为真：

- `hookFrameworkDetected` / `propertyTampered`
- `isRooted` / `magiskDetected` / `kernelsuDetected` / `apatchDetected` / `systemSuDetected`
- `magiskHideSuspected` / `rootAccessGranted`
- 模拟器 / VPN / Debug / ADB 等环境项

本次 JSON 仅 **Magisk Native 归因** 相关项为真。

---

## 3. 四框架检测面分析（通用 + 跨 App）

`RootFrameworkDetector` 对四类 Root 方案使用 **同一套分层模型**。业务 App（银行、游戏、社交等）常复用类似手段，不限于 `com.android.device` 的 JSON 字段名。

### 3.1 检测分层（四类共用）

| 层级 | 典型手段 | device 采集位置 | YumyHook 对应隐藏 |
|------|----------|-----------------|-------------------|
| **路径** | `File.exists`、清单路径扫描 | `scanExistingPaths(*_PATHS)` | `SensitivePathStealthHook`、`Os.access/stat` |
| **Shell** | `which su`、`magisk -v`、`ksud -V`、`apd -V`、`cat /proc/mounts` | `collect*ShellHits` | `ShellProbeStealthHook`、`Runtime.exec`/`ProcessBuilder` |
| **属性** | `getprop`、环境变量 | `allProps`、`envHits` | 四通道 + getprop Hook |
| **包名** | 已安装 Magisk/KSU/APatch 管理器 | `installedPackages` | `PackageHideStealthHook` |
| **/proc** | maps / mount / mountinfo 关键词 | `scanProcFile`、`scanProcMounts` | `ProcMapsStealthHook`、`ProcFsRedirect` |
| **Native** | `access()` 路径 + `fopen` 读 maps/mountinfo | `device.cpp` → `getMagiskNativeProbe` | `native_bridge.cpp`：`access`/`faccessat`/`__syscall` + `fopen`/`fgets` 行过滤 |
| **su 授权** | 实际执行 `su` | `RootAccessHelper` | DenyList（非 Hook 面） |
| **Java/Native 不一致** | Native 可见、Java 不可见 | `javaNativeMismatches` | **必须双层对齐** |

### 3.2 Magisk（含 Zygisk）

**device 关键词 / 路径（节选）**

- 路径：`/sbin/magisk`、`/data/adb/magisk`、`/debug_ramdisk/magisk`、`/data/adb/modules` 等
- maps/mount：`magisk`、`zygisk`、`magiskpolicy`、`resetprop`
- Shell：`getprop | grep magisk`、`which magisk`、`magisk -v`、`resetprop`
- Native（`device.cpp`）：同上路径 `access()` + maps/mountinfo 关键词扫描

**本次 JSON**

- Java 路径 / Shell / 包：**未命中**
- Native：`accessiblePaths=[/debug_ramdisk/magisk]`，`mapsHits=[zygisk×3]`，`mountHits=[magisk×5]`，`anyHit=true`
- 归因：`magiskDetected=true`，`frameworkConfirmed=true`

**其他 App 已证异常场景**

- 直接 JNI / NDK 读 `/proc/self/maps` 搜 `zygisk`（不经 Java `BufferedReader`）
- Native `access("/debug_ramdisk/magisk")` 或扫 `/data/adb/*`
- 挂载表 / overlay 特征（`magisk` in mountinfo）
- 部分 App 检测 `resetprop`、Zygisk 注入 so 名

**YumyHook 缺口**：Java 已 Hide，**Native `access` + `fgets` 读真实 proc 仍泄漏** → 需 `native_bridge.cpp` REV≥71 面全覆盖并实机验证。

### 3.3 KernelSU

**device 关键词 / 路径（节选）**

- 路径：`/data/adb/ksu`、`/data/adb/kernelsu` 等
- maps：`kernelsu`、`ksud`；**精确** token `ksu`（短词 ≥5 字符规则，避免误匹配）
- Shell：`ksud -V`、`which ksud`、`ls /data/adb/ksu`、`getprop | grep ksu`、`/proc/version` 含 kernelsu
- Native：`kernelsu`、`ksud` 关键词；路径 `/data/adb/ksu`、`/data/adb/kernelsu`

**本次 JSON**：`kernelsuDetected=false`（本机主要为 Magisk+Zygisk；maps 中 `ksu` 可能被 Magisk 模块带入，但 Native 归因逻辑以 `zygisk`/`magisk` 为主）。

**其他 App 已证异常场景**

- 内核 Root 管理器包名、`:magisk` 类模块共存时 maps 混有 `ksu`/`kernelsu`
- 独立 KSU 环境：`ksud` 二进制、`/data/adb/ksu` 目录可访问
- 部分检测读 `/proc/version` 或自定义 ioctl

**YumyHook 要求**：maps 过滤须含 `kernelsu`/`ksud`；路径隐藏含 `/data/adb/ksu`；**禁止**仅针对 `com.android.device` 特化。

### 3.4 APatch

**device 关键词 / 路径（节选）**

- 路径：`/data/adb/ap`、`/data/adb/apd`
- maps/mount：`apatch`、`bmax`；路径前缀 `/data/adb/ap`、`/data/adb/apd`（短词 `apd` 需 ≥5 字符或路径匹配）
- Shell：`apd -V`、`which apd`、`ls /data/adb/ap`、`getprop | grep apatch`
- Native：`apatch`、`/data/adb/ap`、`/data/adb/apd`

**本次 JSON**：`apatchDetected=false`。

**其他 App 已证异常场景**

- APatch 独立安装时 `/data/adb/apd` 可访问
- CLI `apd`、环境变量、模块目录
- 与 Magisk 共存时需避免 APatch 路径从 Native 泄漏

**YumyHook 要求**：Native 路径列表与行过滤须覆盖 `apatch`/`apd`/`/data/adb/ap`；Shell `apd`/`which` 走通用 Shell 隐藏。

### 3.5 系统 Root（su）

**device 关键词 / 路径（节选）**

- 路径：`/system/xbin/su`、`/system/bin/su`（**Native 路径列表已移除 `/system/bin/su`**，避免与 Hide 不一致误报）
- Shell：`which su`、`su -v`、`stat /system/bin/su`、`readlink /system/bin/su`
- maps：`supersu`、`daemonsu`（Native 关键词）
- 指标：`suBinaryExists`、`whichSu`、`rootedSystemProperty` 等经典项

**本次 JSON**：`systemSuDetected=false`；经典 indicators 均为 false。

**其他 App 已证异常场景**

- 直接调 `su` 或检测 `which su` 非空
- 系统分区 su 二进制、SuperSU 残留路径
- `ro.debuggable` / `ro.secure` 等属性组合（走四通道）

**YumyHook 要求**：`SensitivePathStealthHook` + Shell；**不再**在 Native `access` 列表中扫系统 `su`（与 device 对齐，减少 javaNative 误不一致）。

### 3.6 跨 App 与 reference App 的关系

| 维度 | `com.android.device` | 其他业务 App |
|------|----------------------|--------------|
| 角色 | 回归对照、四通道 + Root 全量 JSON | 黑盒，往往只用子集向量 |
| 结论 | 当前卡在 **Native probe** | 同类 Native/maps/su 探测仍可能单独判异常 |
| YumyHook 原则 | 对照 JSON 回归 | 系统层通用 Hook，**禁止**单包特化 |

**实践含义**：即使 `hideSuspected=false` 且 Java 全绿，只要 **任一类框架** 在 Native 或 su 链路上泄漏，其他 App 仍可能报「已 Root / Magisk / KSU / APatch」。四框架须 **同一套 Native+Java 双层** 一起补齐。

---

## 4. 本次 JSON 关键字段（`2026-07-07_095308`）

### 4.1 Summary

```json
{
  "hookFrameworkDetected": false,
  "propertyTampered": false,
  "isRooted": true,
  "frameworkConfirmed": true,
  "magiskDetected": true,
  "kernelsuDetected": false,
  "apatchDetected": false,
  "systemSuDetected": false,
  "magiskHideSuspected": false,
  "rootAccessGranted": false,
  "anyRisk": true
}
```

- **Hook / 属性**：`procMapsMatches` 为空；探测属性各通道一致（`tampered: false`）。
- **libcutils**：`property_get unavailable` — 设备 so 缺失，**不计入** `propertyTampered`（四通道独立问题）。
- **Root 经典指标**：`suBinaryExists`、`magiskPathExists` 等均为 false。

### 4.2 `sharedIndicators.nativeProbe`

```json
{
  "accessiblePaths": ["/debug_ramdisk/magisk"],
  "mapsHits": ["zygisk", "zygisk", "zygisk"],
  "mountHits": ["magisk", "magisk", "magisk", "magisk", "magisk"],
  "anyHit": true
}
```

### 4.3 Java / Native 不一致

```json
"javaNativeMismatches": [
  "/debug_ramdisk/magisk (native可访问, Java=false)"
]
```

Java `File.exists` 已 Hide；Native `access()` 仍成功 → 在新逻辑下主要推高 **`magiskDetected`**（经 `applyNativeProbeToFrameworks`），而非单独 `hideSuspected`。

### 4.4 `anyRiskReasons` / `anyRiskFixHints` / `remediation`

采集后 `debug_output.json` 含三层提示（由 `SecurityRemediationBuilder` 自动生成）：

| 字段 | 用途 |
|------|------|
| `anyRiskReasons` | 简短判定原因（人读） |
| `anyRiskFixHints` | 一行一条：`检测现象 \| 修复动作 @ 目标模块` |
| `security.remediation` | 结构化修复清单（对接 YumyHook 主用） |

**本次 `anyRiskReasons`**

```json
[
  "Native 探测命中 Magisk/Zygisk",
  "Native 路径可访问: /debug_ramdisk/magisk"
]
```

**`anyRiskFixHints` 示例**

```json
[
  "Native access() 可访问: /debug_ramdisk/magisk | 修复: Hook access/faccessat/... 返回 -1 @ YumyHook/native_bridge.cpp",
  "Native 读 /proc/self/maps 命中: zygisk | 修复: Hook fopen+fgets 过滤 zygisk 行 @ YumyHook/native_bridge.cpp"
]
```

**`security.remediation.items[]` 单条结构**

```json
{
  "id": "native_access__debug_ramdisk_magisk",
  "severity": "critical",
  "category": "root_native",
  "framework": "magisk",
  "detection": "Native access() 可访问: /debug_ramdisk/magisk",
  "fixTarget": "YumyHook/native_bridge.cpp",
  "fixAction": "Hook access/faccessat/__syscall(faccessat)，对该路径返回 -1 (ENOENT)",
  "verify": "nativeProbe.accessiblePaths 不含 /debug_ramdisk/magisk",
  "detectorSource": "device.cpp/getMagiskNativeProbe + RootFrameworkDetector"
}
```

**`remediation.verdict`**

- `RISK_DETECTED` — 需修复后重新采集
- `PASS` — `anyRisk=false`，无需动作

---

## 5. 各检测模块表现

| 模块 | 文件 | 本次结果 |
|------|------|----------|
| Hook 框架类/文件/maps | `CheckEmu` | 未命中 |
| 属性多通道 | `CheckEmu.probeProperty` | 未篡改 |
| Root 路径 / su / which | `CheckEmu.buildRootIndicators` | 未命中 |
| 框架分类（Java only） | `RootFrameworkDetector.probeFramework` | 四类 Java 路径均未可见 |
| **Native 探测 + 归因** | `device.cpp` + `applyNativeProbeToFrameworks` | **Magisk 确认** |
| su 授权 | `RootAccessHelper` | 拒绝 |

---

## 6. Hide 已生效 vs 未生效

### 已 Hide

- Java 文件路径扫描（含四框架路径表）
- Shell（`getprop`、`which su/magisk/ksud/apd` 等）
- su 授权（DenyList）
- 系统属性（`release-keys`、`verifiedbootstate` 等）
- Java 层 `/proc/self/maps|mountinfo` 关键词
- `hideSuspected` 误判源（Boot 解锁、system_overlay、短词 `ksu`/`apd`）

### 未 Hide（当前风控命中）

- Native `JniInterface.getMagiskNativeProbe()`：`access()` + `fopen`/`fgets` on maps/mountinfo
- `/debug_ramdisk/magisk` Native 可访问
- maps 中 `zygisk`、mount 中 `magisk` 行未被 Native 层过滤

---

## 7. YumyHook 对接：按 remediation 逐项修复

> **工作流**：安装 Hook → 打开 `com.android.device` 采集 → 拉取 `/sdcard/Download/debug_output.json` → 看 `remediation.summary` 与 `items[]` → 修完再采集直到 `verdict=PASS`。

### 7.1 当前场景（Native 泄漏）修复顺序

| 顺序 | remediation.id 前缀 | 要改哪里 | 要做什么 |
|------|---------------------|----------|----------|
| 1 | `native_access_` | `YumyHook/native_bridge.cpp` | `access`/`faccessat`/`__syscall`(faccessat) 对 Magisk 路径表返回失败（含 `/debug_ramdisk/magisk`） |
| 2 | `native_maps_` | 同上 | `fopen`+`fgets` 读 `/proc/self/maps` 时过滤 `zygisk`/`magisk`/`kernelsu` 等行 |
| 3 | `native_mount_` | 同上 | `fopen`+`fgets` 读 `/proc/self/mountinfo` 时过滤 `magisk` 等行 |
| 4 | `root_java_native_mismatch` | 同上 + `SensitivePathStealthHook.kt` | Java `File.exists=false` 的路径，Native `access` 也必须失败 |
| 5 | `root_magisk_confirmed` | 综合 | 上述完成后 `magiskDetected`/`frameworkConfirmed` 应降为 false |

**device 侧检测源码（对方只读对照）**

- JNI 入口：`com.android.device.Jni.JniInterface.getMagiskNativeProbe`
- Native 实现：`deviceinfo_collect/app/src/main/cpp/device.cpp`（`access` + `append_keyword_hits`）
- 归因逻辑：`RootFrameworkDetector.applyNativeProbeToFrameworks`

### 7.2 四框架通用修复表（非仅 device）

| 框架 | Native 路径（须 access 失败） | maps/mount 须过滤关键词 |
|------|------------------------------|-------------------------|
| Magisk | `/sbin/magisk`, `/data/adb/magisk`, `/debug_ramdisk/magisk`, `/data/adb/modules` | `magisk`, `zygisk`, `magiskpolicy`, `resetprop` |
| KernelSU | `/data/adb/ksu`, `/data/adb/kernelsu` | `kernelsu`, `ksud` |
| APatch | `/data/adb/ap`, `/data/adb/apd` | `apatch`, `/data/adb/ap` |
| 系统 su | Java/Shell 层（Native 不扫 `/system/bin/su`） | `supersu`, `daemonsu` |

### 7.3 回归检查（`remediation.regressionChecklist`）

采集通过后应满足：

```
security.summary.anyRisk                    → false
security.summary.magiskDetected             → false
security.summary.frameworkConfirmed         → false
security.root.rootProbe.sharedIndicators.nativeProbe.anyHit → false
security.remediation.verdict                → PASS
```

logcat：`adb logcat -s YH-NATIVE-STEALTH`，确认本进程 `access`/`fgets` Hook 命中（`HOOK_REV≥71`）。

### 7.4 非 Root 类条目（若出现）

| category | fixTarget | 说明 |
|----------|-----------|------|
| `hook_property` | YumyHook 四通道属性 | 对齐 getprop/SystemProperties/jniGet/jniFind |
| `hook_proc` | ProcMapsStealthHook + native_bridge | Java+Native 双层过滤 maps |
| `root_su` + `root_su_granted` | DenyList | su 授权只能拒绝，不能只靠 Hook 返回假结果 |
| `bootloader` | 无需修复 | `bootloaderUnlocked` 信息项，不单独拉高 anyRisk |

---

## 8. device 侧误判修复记录（已实现）

| 风险 | 说明 | 修复 |
|------|------|------|
| 仅 Bootloader 解锁 | `verifiedbootstate=orange` ≠ Root Hide | Boot 解锁不再触发 `hideSuspected`；`bootloaderUnlocked` 独立字段 |
| `system_overlay` | 正版 overlayfs 挂载 | 移除通用 overlay 命中 |
| 短关键词 `ksu`/`apd` | maps 子串误匹配 | Java/Native ≥5 字符或路径前缀匹配 |
| Native 命中未归因 | `magiskDetected=false` 但 Native 有信号 | `applyNativeProbeToFrameworks` 回写框架 `detected` |
| `isRooted` 仅因疑似 | 疑似隐藏 ≠ 确认 Root | `isRooted` 仅 `frameworkConfirmed` 或经典指标 |
| 重复 reason | rootProbe 与 buildRootReasons 双写 | 去重 |
| Native 扫系统 su | `/system/bin/su` 引发误不一致 | Native 路径列表移除系统 su |

---

## 9. 相关源码索引

| 职责 | 路径（deviceinfo_collect） |
|------|---------------------------|
| 安全报告聚合 | `app/.../SecurityReportComposer.java` |
| Hook / 属性探测 | `app/.../CheckEmu.java` |
| Root 框架探测 | `app/.../RootFrameworkDetector.java` |
| su 授权探测 | `app/.../RootAccessHelper.java` |
| Native Root 探测 | `app/src/main/cpp/device.cpp` |
| 采集与导出 | `app/.../DeviceSnapshotMerger.java` |
| **修复指引生成** | `app/.../SecurityRemediationBuilder.java` |

| 职责 | 路径（YumyHook） |
|------|------------------|
| Native proc/root 隐藏 | `app/src/main/cpp/native_bridge.cpp` |
| Java Native API 隐藏 | `xposed/stealth/hide/NativeApiStealthHook.kt` |
| 路径 / proc maps | `SensitivePathStealthHook.kt`、`ProcMapsStealthHook.kt` |
| 回归红线 | `.cursor/rules/yumyhook-regression.mdc` |

---

---

## 10. debug_output.json 顶层字段速查

| 字段 | 说明 |
|------|------|
| `anyRisk` | 是否存在安全风险 |
| `anyRiskReasons` | 简短原因列表 |
| `anyRiskFixHints` | 带修复动作的一行提示 |
| `remediation` | 顶层摘要（详单在 `security.remediation`） |
| `security.remediation` | 完整修复项 + 回归清单 + YumyHook 路径引用 |

**给对方（YumyHook）的最短阅读路径**

1. `security.remediation.summary`
2. `security.remediation.items[]` — 按 `severity` 从高到低处理
3. 每修一项对照 `verify` 字段
4. 全部修完后重采，确认 `security.remediation.verdict === "PASS"`

---

*文档版本：含 remediation 输出 + YumyHook 对接指南 · Yumito · 2026-07-07*
