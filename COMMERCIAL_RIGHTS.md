# 商用权利说明

## 许可概览

| 主体 | 权利 |
|------|------|
| **公众 / 第三方** | [CC BY-NC 4.0](LICENSE) — 可学习、修改、再分发，须署名，**禁止商用** |
| **版权人 Yumito（张宸硕）** | 保留**独占商用权**；本人未来商业化不受 NC 条款限制 |

> CC BY-NC 4.0 约束的是**被许可方（他人）**，不约束**许可方（版权人）**。  
> 版权人始终对自己的作品享有完整著作权，包括自行商用、授权他人商用的权利。

## 第三方可以做什么

- 个人学习、研究、非盈利分享
- Fork 并修改，须保留署名与许可声明
- 在作品/分发中注明来源：`https://gitee.com/Yumito/yumy-hook`

## 第三方不可以做什么（未经书面授权）

- 将本项目或衍生作品用于盈利产品/服务
- 出售、出租、SaaS 化、嵌入商业 SDK
- 移除或篡改下文所述的**谱系指纹埋点**

## 商用授权

如需商用，请联系版权人（Gitee [@Yumito](https://gitee.com/Yumito)）获取**独立商业许可**。  
CC BY-NC 4.0 本身不提供商业授权通道。

---

## 谱系指纹埋点（溯源追踪）

为识别未授权商用或篡改分发，源码与 APK 产物内嵌以下标记。**请勿删除。**

### 固定谱系 ID（跨版本不变）

```
YH-LIN-8d4e2f91-yumito
```

### 埋点位置

| 位置 | 内容 |
|------|------|
| `ProjectAttribution.kt` | 版权人、许可 ID、谱系指纹、构建戳 |
| `assets/attribution.json` | 机器可读元数据（解压 APK 可见） |
| `AndroidManifest.xml` | `meta-data`：`yumyhook.license` / `lineage` / `copyright` |
| `native_bridge.cpp` | Native 层水印字符串 |
| 运行时日志 | `YumyHook: attribution …`（logcat 可检索） |

### 构建戳（每 commit 变化）

Gradle 将 Git 短哈希写入 `BuildConfig.BUILD_STAMP`，格式示例：

```
YH-LIN-8d4e2f91-yumito|CC-BY-NC-4.0|Yumito|build=6b3c8bb
```

### 如何查验可疑分发

```bash
# 1. 解压 APK 查看资产
unzip -p app.apk assets/attribution.json

# 2. 查看 Manifest 元数据
aapt dump xmltree app.apk AndroidManifest.xml | grep yumyhook

# 3. 运行时日志
adb logcat -s YumyHook | grep attribution

# 4. 反编译检索固定指纹
rg "YH-LIN-8d4e2f91-yumito" decompiled/
```

若发现商用分发且指纹指向本仓库谱系，可据此追溯来源并主张权利。
