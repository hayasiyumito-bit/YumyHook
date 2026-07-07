package com.yumito.yumyhook

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.google.android.material.materialswitch.MaterialSwitch
import com.yumito.yumyhook.databinding.ActivityMainBinding
import com.yumito.yumyhook.ui.ImmersiveUi
import com.yumito.yumyhook.ui.config.ConfigEditActivity
import com.yumito.yumyhook.feature.home.MainViewModel
import androidx.recyclerview.widget.LinearLayoutManager
import com.yumito.yumyhook.ui.home.ScopeAppAdapter

/** 模块主页：Xposed 状态、Hook 总开关、伪装参数预览。 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private var hookSwitch: MaterialSwitch? = null
    private var suppressHookToggle = false
    private var suppressFrameworkToggle = false
    private val scopeAppAdapter = ScopeAppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.viewModel = viewModel

        ImmersiveUi.apply(this, binding.appBar, binding.scrollMain)
        ProjectAttribution.emitAppAttribution()

        setSupportActionBar(binding.toolbar)

        binding.rvScopeApps.layoutManager = LinearLayoutManager(this)
        binding.rvScopeApps.adapter = scopeAppAdapter
        viewModel.status.observe(this) { status ->
            scopeAppAdapter.submit(status?.scopedApps.orEmpty())
        }

        viewModel.hookEnabled.observe(this) { enabled ->
            hookSwitch?.let { switch -> suppressHookToggle = true
                switch.isChecked = enabled
                switch.isEnabled = viewModel.hookBusy.value != true
                suppressHookToggle = false
            }
        }

        viewModel.hookBusy.observe(this) { busy ->
            hookSwitch?.isEnabled = !busy
        }

        viewModel.frameworkHideRoot.observe(this) { enabled ->
            suppressFrameworkToggle = true
            binding.switchFrameworkHideRoot.isChecked = enabled
            suppressFrameworkToggle = false
        }
        viewModel.frameworkHideMagisk.observe(this) { enabled ->
            suppressFrameworkToggle = true
            binding.switchFrameworkHideMagisk.isChecked = enabled
            suppressFrameworkToggle = false
        }
        binding.switchFrameworkHideRoot.setOnCheckedChangeListener { _, checked ->
            if (suppressFrameworkToggle) return@setOnCheckedChangeListener
            viewModel.setFrameworkHideRoot(checked)
        }
        binding.switchFrameworkHideMagisk.setOnCheckedChangeListener { _, checked ->
            if (suppressFrameworkToggle) return@setOnCheckedChangeListener
            viewModel.setFrameworkHideMagisk(checked)
        }

        viewModel.sessionMessage.observe(this) { message ->
            if (message.isNullOrBlank()) return@observe
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            viewModel.consumeSessionMessage()
        }

        binding.btnRefresh.setOnClickListener { viewModel.refresh(useRoot = true) }
        binding.btnOpenConfig.setOnClickListener {
            startActivity(Intent(this, ConfigEditActivity::class.java))
        }
        binding.btnRandomizeSpoof.setOnClickListener { viewModel.randomizeSpoof() }

        viewModel.refreshOnOpen()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val item = menu.findItem(R.id.action_hook_switch)
        hookSwitch = item.actionView?.findViewById(R.id.switch_hook_enabled)
        hookSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressHookToggle) return@setOnCheckedChangeListener
            viewModel.setHookEnabled(checked)
        }
        viewModel.hookEnabled.value?.let { enabled ->
            suppressHookToggle = true
            hookSwitch?.isChecked = enabled
            suppressHookToggle = false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSpoofOnly()
    }
}
