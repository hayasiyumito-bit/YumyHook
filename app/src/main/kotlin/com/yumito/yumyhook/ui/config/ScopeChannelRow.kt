package com.yumito.yumyhook.ui.config

data class ScopeChannelRow(
    val packageName: String,
    val label: String,
    val javaChannelEnabled: Boolean,
    val nativeChannelEnabled: Boolean,
    val nativeSwitchEnabled: Boolean = true,
)
