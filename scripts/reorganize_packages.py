#!/usr/bin/env python3
"""One-shot package reorg for YumyHook Kotlin sources."""
from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/com/yumito/yumyhook"

# (old_rel_path, new_rel_path, new_package)
MOVES: list[tuple[str, str, str]] = [
    # data
    ("util/HookProfilesStore.kt", "data/profile/HookProfilesStore.kt", "com.yumito.yumyhook.data.profile"),
    ("util/HookConfigPublisher.kt", "data/publish/HookConfigPublisher.kt", "com.yumito.yumyhook.data.publish"),
    ("util/LsposedConfigReader.kt", "data/lsposed/LsposedConfigReader.kt", "com.yumito.yumyhook.data.lsposed"),
    ("util/LsposedScopeReader.kt", "data/lsposed/LsposedScopeReader.kt", "com.yumito.yumyhook.data.lsposed"),
    ("util/RootShell.kt", "data/lsposed/RootShell.kt", "com.yumito.yumyhook.data.lsposed"),
    ("util/ScopeLabelResolver.kt", "data/lsposed/ScopeLabelResolver.kt", "com.yumito.yumyhook.data.lsposed"),
    ("util/XposedStatusChecker.kt", "data/lsposed/XposedStatusChecker.kt", "com.yumito.yumyhook.data.lsposed"),
    # feature
    ("util/HookSessionController.kt", "feature/session/HookSessionController.kt", "com.yumito.yumyhook.feature.session"),
    ("util/ConfigDebugLog.kt", "feature/config/ConfigDebugLog.kt", "com.yumito.yumyhook.feature.config"),
    ("ui/main/MainViewModel.kt", "feature/home/MainViewModel.kt", "com.yumito.yumyhook.feature.home"),
    # xposed entry + config
    ("xposed/XposedEntry.kt", "xposed/entry/XposedEntry.kt", "com.yumito.yumyhook.xposed.entry"),
    ("xposed/HookConfig.kt", "xposed/config/HookConfig.kt", "com.yumito.yumyhook.xposed.config"),
    ("xposed/HookFeatureConfig.kt", "xposed/config/HookFeatureConfig.kt", "com.yumito.yumyhook.xposed.config"),
    ("xposed/SpoofConfigFile.kt", "xposed/config/SpoofConfigFile.kt", "com.yumito.yumyhook.xposed.config"),
    ("xposed/HookSpoofValues.kt", "xposed/config/HookSpoofValues.kt", "com.yumito.yumyhook.xposed.config"),
    ("xposed/XposedConstants.kt", "xposed/config/XposedConstants.kt", "com.yumito.yumyhook.xposed.config"),
    # xposed channel
    ("xposed/hook/SystemHookInstaller.kt", "xposed/channel/SystemHookInstaller.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/hook/OsBuildHook.kt", "xposed/channel/OsBuildHook.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/hook/SystemPropertiesHook.kt", "xposed/channel/SystemPropertiesHook.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/hook/GetpropHook.kt", "xposed/channel/GetpropHook.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/OsBuildPatcher.kt", "xposed/channel/OsBuildPatcher.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/GetpropMerger.kt", "xposed/channel/GetpropMerger.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/SystemPropertyMapper.kt", "xposed/channel/SystemPropertyMapper.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/NativeBridge.kt", "xposed/channel/NativeBridge.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/NativeLibLoader.kt", "xposed/channel/NativeLibLoader.kt", "com.yumito.yumyhook.xposed.channel"),
    ("xposed/BuildSpoofGenerator.kt", "xposed/channel/BuildSpoofGenerator.kt", "com.yumito.yumyhook.xposed.channel"),
    # xposed policy + runtime
    ("xposed/FourChannelGate.kt", "xposed/policy/FourChannelGate.kt", "com.yumito.yumyhook.xposed.policy"),
    ("xposed/FourChannelPolicy.kt", "xposed/policy/FourChannelPolicy.kt", "com.yumito.yumyhook.xposed.policy"),
    ("xposed/NativeHookPolicy.kt", "xposed/policy/NativeHookPolicy.kt", "com.yumito.yumyhook.xposed.policy"),
    ("xposed/HookScope.kt", "xposed/policy/HookScope.kt", "com.yumito.yumyhook.xposed.policy"),
    ("xposed/SpoofRuntime.kt", "xposed/runtime/SpoofRuntime.kt", "com.yumito.yumyhook.xposed.runtime"),
    ("xposed/TargetContextHolder.kt", "xposed/runtime/TargetContextHolder.kt", "com.yumito.yumyhook.xposed.runtime"),
    ("xposed/ModulePathHolder.kt", "xposed/runtime/ModulePathHolder.kt", "com.yumito.yumyhook.xposed.runtime"),
    ("xposed/ModuleRuntimeState.kt", "xposed/runtime/ModuleRuntimeState.kt", "com.yumito.yumyhook.xposed.runtime"),
    ("xposed/HookReentryGuard.kt", "xposed/runtime/HookReentryGuard.kt", "com.yumito.yumyhook.xposed.runtime"),
]

STEALTH_DIR = ROOT / "xposed/hook/stealth"
if STEALTH_DIR.exists():
    for f in sorted(STEALTH_DIR.glob("*.kt")):
        MOVES.append(
            (
                f.relative_to(ROOT).as_posix(),
                f"xposed/stealth/{f.name}",
                "com.yumito.yumyhook.xposed.stealth",
            )
        )

DELETE = [
    ROOT / "util/HookPrefs.kt",
    ROOT / "util/HookProfileStore.kt",
]

# Longest-first import rewrites
IMPORT_REWRITES = [
    ("com.yumito.yumyhook.xposed.hook.stealth.", "com.yumito.yumyhook.xposed.stealth."),
    ("com.yumito.yumyhook.xposed.hook.", "com.yumito.yumyhook.xposed.channel."),
    ("com.yumito.yumyhook.util.HookProfileStore", "com.yumito.yumyhook.data.profile.HookProfilesStore"),
    ("com.yumito.yumyhook.util.HookProfilesStore", "com.yumito.yumyhook.data.profile.HookProfilesStore"),
    ("com.yumito.yumyhook.util.HookProfile", "com.yumito.yumyhook.data.profile.HookProfile"),
    ("com.yumito.yumyhook.util.HookConfigPublisher", "com.yumito.yumyhook.data.publish.HookConfigPublisher"),
    ("com.yumito.yumyhook.util.LsposedConfigReader", "com.yumito.yumyhook.data.lsposed.LsposedConfigReader"),
    ("com.yumito.yumyhook.util.LsposedScopeReader", "com.yumito.yumyhook.data.lsposed.LsposedScopeReader"),
    ("com.yumito.yumyhook.util.RootShell", "com.yumito.yumyhook.data.lsposed.RootShell"),
    ("com.yumito.yumyhook.util.ScopeLabelResolver", "com.yumito.yumyhook.data.lsposed.ScopeLabelResolver"),
    ("com.yumito.yumyhook.util.XposedStatusChecker", "com.yumito.yumyhook.data.lsposed.XposedStatusChecker"),
    ("com.yumito.yumyhook.util.HookSessionController", "com.yumito.yumyhook.feature.session.HookSessionController"),
    ("com.yumito.yumyhook.util.HookSessionResult", "com.yumito.yumyhook.feature.session.HookSessionResult"),
    ("com.yumito.yumyhook.util.ConfigDebugLog", "com.yumito.yumyhook.feature.config.ConfigDebugLog"),
    ("com.yumito.yumyhook.ui.main.MainViewModel", "com.yumito.yumyhook.feature.home.MainViewModel"),
    ("com.yumito.yumyhook.xposed.XposedEntry", "com.yumito.yumyhook.xposed.entry.XposedEntry"),
    ("com.yumito.yumyhook.xposed.HookConfig", "com.yumito.yumyhook.xposed.config.HookConfig"),
    ("com.yumito.yumyhook.xposed.HookFeatureConfig", "com.yumito.yumyhook.xposed.config.HookFeatureConfig"),
    ("com.yumito.yumyhook.xposed.SpoofConfigFile", "com.yumito.yumyhook.xposed.config.SpoofConfigFile"),
    ("com.yumito.yumyhook.xposed.HookSpoofValues", "com.yumito.yumyhook.xposed.config.HookSpoofValues"),
    ("com.yumito.yumyhook.xposed.XposedConstants", "com.yumito.yumyhook.xposed.config.XposedConstants"),
    ("com.yumito.yumyhook.xposed.SystemHookInstaller", "com.yumito.yumyhook.xposed.channel.SystemHookInstaller"),
    ("com.yumito.yumyhook.xposed.OsBuildHook", "com.yumito.yumyhook.xposed.channel.OsBuildHook"),
    ("com.yumito.yumyhook.xposed.SystemPropertiesHook", "com.yumito.yumyhook.xposed.channel.SystemPropertiesHook"),
    ("com.yumito.yumyhook.xposed.GetpropHook", "com.yumito.yumyhook.xposed.channel.GetpropHook"),
    ("com.yumito.yumyhook.xposed.OsBuildPatcher", "com.yumito.yumyhook.xposed.channel.OsBuildPatcher"),
    ("com.yumito.yumyhook.xposed.GetpropMerger", "com.yumito.yumyhook.xposed.channel.GetpropMerger"),
    ("com.yumito.yumyhook.xposed.SystemPropertyMapper", "com.yumito.yumyhook.xposed.channel.SystemPropertyMapper"),
    ("com.yumito.yumyhook.xposed.NativeBridge", "com.yumito.yumyhook.xposed.channel.NativeBridge"),
    ("com.yumito.yumyhook.xposed.NativeLibLoader", "com.yumito.yumyhook.xposed.channel.NativeLibLoader"),
    ("com.yumito.yumyhook.xposed.BuildSpoofGenerator", "com.yumito.yumyhook.xposed.channel.BuildSpoofGenerator"),
    ("com.yumito.yumyhook.xposed.FourChannelGate", "com.yumito.yumyhook.xposed.policy.FourChannelGate"),
    ("com.yumito.yumyhook.xposed.FourChannelPolicy", "com.yumito.yumyhook.xposed.policy.FourChannelPolicy"),
    ("com.yumito.yumyhook.xposed.NativeHookPolicy", "com.yumito.yumyhook.xposed.policy.NativeHookPolicy"),
    ("com.yumito.yumyhook.xposed.HookScope", "com.yumito.yumyhook.xposed.policy.HookScope"),
    ("com.yumito.yumyhook.xposed.SpoofRuntime", "com.yumito.yumyhook.xposed.runtime.SpoofRuntime"),
    ("com.yumito.yumyhook.xposed.TargetContextHolder", "com.yumito.yumyhook.xposed.runtime.TargetContextHolder"),
    ("com.yumito.yumyhook.xposed.ModulePathHolder", "com.yumito.yumyhook.xposed.runtime.ModulePathHolder"),
    ("com.yumito.yumyhook.xposed.ModuleRuntimeState", "com.yumito.yumyhook.xposed.runtime.ModuleRuntimeState"),
    ("com.yumito.yumyhook.xposed.HookReentryGuard", "com.yumito.yumyhook.xposed.runtime.HookReentryGuard"),
    ("import com.yumito.yumyhook.util.isEnabled", "import com.yumito.yumyhook.model.HookFeatures"),
]

PACKAGE_LINE = re.compile(r"^package\s+[\w.]+", re.MULTILINE)


def set_package(content: str, package: str) -> str:
    if PACKAGE_LINE.search(content):
        return PACKAGE_LINE.sub(f"package {package}", content, count=1)
    return f"package {package}\n\n{content}"


def rewrite_imports(content: str) -> str:
    for old, new in IMPORT_REWRITES:
        content = content.replace(old, new)
    # HookProfileStore -> HookProfilesStore for method calls
    content = content.replace("HookProfileStore.", "HookProfilesStore.")
    content = content.replace("HookPrefs.", "HookProfilesStore.")
    return content


def move_files() -> None:
    for old_rel, new_rel, pkg in MOVES:
        src = ROOT / old_rel
        dst = ROOT / new_rel
        if not src.exists():
            print(f"skip missing: {old_rel}")
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        text = src.read_text(encoding="utf-8")
        text = set_package(text, pkg)
        dst.write_text(text, encoding="utf-8")
        if src.resolve() != dst.resolve():
            src.unlink()
        print(f"moved {old_rel} -> {new_rel}")


def rewrite_all_kotlin() -> None:
    for path in ROOT.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        new_text = rewrite_imports(text)
        if new_text != text:
            path.write_text(new_text, encoding="utf-8")


def cleanup() -> None:
    for p in DELETE:
        if p.exists():
            p.unlink()
            print(f"deleted {p.relative_to(ROOT)}")
    for empty_dir in [ROOT / "util", ROOT / "ui/main", ROOT / "xposed/hook/stealth", ROOT / "xposed/hook"]:
        if empty_dir.exists() and not any(empty_dir.rglob("*")):
            empty_dir.rmdir()
            print(f"removed empty {empty_dir.relative_to(ROOT)}")


def main() -> None:
    move_files()
    rewrite_all_kotlin()
    cleanup()
    print("done")


if __name__ == "__main__":
    main()
