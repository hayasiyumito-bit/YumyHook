#!/usr/bin/env python3
"""Add missing cross-package imports after YumyHook reorg."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/com/yumito/yumyhook"

SYMBOL_IMPORTS: dict[str, str] = {
    "HookConfig": "com.yumito.yumyhook.xposed.config.HookConfig",
    "HookFeatureConfig": "com.yumito.yumyhook.xposed.config.HookFeatureConfig",
    "HookSpoofValues": "com.yumito.yumyhook.xposed.config.HookSpoofValues",
    "SpoofConfigFile": "com.yumito.yumyhook.xposed.config.SpoofConfigFile",
    "XposedConstants": "com.yumito.yumyhook.xposed.config.XposedConstants",
    "FourChannelGate": "com.yumito.yumyhook.xposed.policy.FourChannelGate",
    "FourChannelPolicy": "com.yumito.yumyhook.xposed.policy.FourChannelPolicy",
    "NativeHookPolicy": "com.yumito.yumyhook.xposed.policy.NativeHookPolicy",
    "HookScope": "com.yumito.yumyhook.xposed.policy.HookScope",
    "SpoofRuntime": "com.yumito.yumyhook.xposed.runtime.SpoofRuntime",
    "TargetContextHolder": "com.yumito.yumyhook.xposed.runtime.TargetContextHolder",
    "ModulePathHolder": "com.yumito.yumyhook.xposed.runtime.ModulePathHolder",
    "ModuleRuntimeState": "com.yumito.yumyhook.xposed.runtime.ModuleRuntimeState",
    "HookReentryGuard": "com.yumito.yumyhook.xposed.runtime.HookReentryGuard",
    "NativeBridge": "com.yumito.yumyhook.xposed.channel.NativeBridge",
    "NativeLibLoader": "com.yumito.yumyhook.xposed.channel.NativeLibLoader",
    "OsBuildPatcher": "com.yumito.yumyhook.xposed.channel.OsBuildPatcher",
    "OsBuildHook": "com.yumito.yumyhook.xposed.channel.OsBuildHook",
    "SystemPropertiesHook": "com.yumito.yumyhook.xposed.channel.SystemPropertiesHook",
    "GetpropHook": "com.yumito.yumyhook.xposed.channel.GetpropHook",
    "GetpropMerger": "com.yumito.yumyhook.xposed.channel.GetpropMerger",
    "SystemPropertyMapper": "com.yumito.yumyhook.xposed.channel.SystemPropertyMapper",
    "BuildSpoofGenerator": "com.yumito.yumyhook.xposed.channel.BuildSpoofGenerator",
    "SystemHookInstaller": "com.yumito.yumyhook.xposed.channel.SystemHookInstaller",
    "HookProfilesStore": "com.yumito.yumyhook.data.profile.HookProfilesStore",
    "HookProfile": "com.yumito.yumyhook.data.profile.HookProfile",
    "HookConfigPublisher": "com.yumito.yumyhook.data.publish.HookConfigPublisher",
    "RootShell": "com.yumito.yumyhook.data.lsposed.RootShell",
    "LsposedScopeReader": "com.yumito.yumyhook.data.lsposed.LsposedScopeReader",
    "LsposedConfigReader": "com.yumito.yumyhook.data.lsposed.LsposedConfigReader",
    "ScopeLabelResolver": "com.yumito.yumyhook.data.lsposed.ScopeLabelResolver",
    "XposedStatusChecker": "com.yumito.yumyhook.data.lsposed.XposedStatusChecker",
}

IMPORT_LINE = re.compile(r"^import\s+([\w.]+)", re.MULTILINE)
PACKAGE_LINE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)


def fix_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    pkg_match = PACKAGE_LINE.search(text)
    if not pkg_match:
        return False
    current_pkg = pkg_match.group(1)
    existing = set(IMPORT_LINE.findall(text))
    needed: list[str] = []
    for symbol, fqcn in SYMBOL_IMPORTS.items():
        pkg = fqcn.rsplit(".", 1)[0]
        if pkg == current_pkg:
            continue
        if fqcn in existing:
            continue
        if re.search(rf"\b{symbol}\b", text) is None:
            continue
        needed.append(f"import {fqcn}")
    if not needed:
        return False
    needed = sorted(set(needed))
    lines = text.splitlines(keepends=True)
    insert_at = 0
    for i, line in enumerate(lines):
        if line.startswith("package "):
            insert_at = i + 1
            break
    while insert_at < len(lines) and lines[insert_at].strip() == "":
        insert_at += 1
    block = "".join(line + "\n" for line in needed) + "\n"
    new_text = "".join(lines[:insert_at]) + block + "".join(lines[insert_at:])
    path.write_text(new_text, encoding="utf-8")
    return True


def main() -> None:
    count = 0
    for path in sorted(ROOT.rglob("*.kt")):
        if fix_file(path):
            count += 1
            print(f"fixed imports: {path.relative_to(ROOT)}")
    print(f"updated {count} files")


if __name__ == "__main__":
    main()
