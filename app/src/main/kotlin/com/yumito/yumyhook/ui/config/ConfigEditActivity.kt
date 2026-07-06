package com.yumito.yumyhook.ui.config

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.yumito.yumyhook.R
import com.yumito.yumyhook.databinding.ActivityConfigEditBinding
import com.yumito.yumyhook.model.HookFeatures
import com.yumito.yumyhook.ui.ImmersiveUi
import com.yumito.yumyhook.util.ConfigDebugLog

class ConfigEditActivity : AppCompatActivity() {

    private val viewModel: ConfigEditViewModel by viewModels()
    private lateinit var binding: ActivityConfigEditBinding
    private val buildInputs = linkedMapOf<String, TextInputEditText>()
    private val idsInputs = linkedMapOf<String, TextInputEditText>()
    private var suppressSectionToggle = false
    private var suppressTabSelect = false
    private var suppressDebugToggle = false

    private val featureAdapter = HookFeatureAdapter(
        onToggle = { key, enabled ->
            val ok = viewModel.setFeature(key, enabled)
            if (ok) logFeatureToggle(key, enabled)
            ok
        },
    )

    private val experimentalAdapter = HookFeatureAdapter(
        onToggle = { _, enabled ->
            if (enabled) {
                Toast.makeText(this, R.string.feature_not_implemented, Toast.LENGTH_SHORT).show()
            }
            false
        },
        allowToggle = { true },
    )

    private data class SectionSwitch(
        val key: String,
        val root: View,
        val switch: MaterialSwitch,
    )

    private lateinit var sectionSwitches: List<SectionSwitch>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_config_edit)

        ImmersiveUi.apply(this, binding.appBar, binding.scrollConfig)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        bindDebugLogRow()

        sectionSwitches = listOf(
            section("spoofPartialDeviceId", binding.rowPartialDeviceId),
            section("simSimulation", binding.rowSimSimulation),
            section("spoofFullDeviceId", binding.rowFullDeviceId),
        )

        bindSectionLabels()
        sectionSwitches.forEach { section ->
            section.switch.setOnCheckedChangeListener { _, checked ->
                if (suppressSectionToggle) return@setOnCheckedChangeListener
                if (!viewModel.setFeature(section.key, checked)) {
                    suppressSectionToggle = true
                    section.switch.isChecked = !checked
                    suppressSectionToggle = false
                } else {
                    logFeatureToggle(section.key, checked)
                }
            }
        }

        binding.recyclerFeatures.adapter = featureAdapter
        binding.recyclerExperimental.adapter = experimentalAdapter
        buildBuildFieldInputs()
        buildIdsFieldInputs()

        binding.btnAddProfile.setOnClickListener { showAddProfileDialog() }
        binding.btnDeleteProfile.setOnClickListener { deleteActiveProfile() }
        binding.btnSaveDevice.setOnClickListener {
            val fields = collectBuildFields()
            ConfigDebugLog.logSave(this, "设备参数", fields)
            viewModel.saveBuildFields(fields)
        }
        binding.btnSaveSim.setOnClickListener {
            val fields = collectIdsFields()
            ConfigDebugLog.logSave(this, "SIM 参数", fields)
            viewModel.saveIdsFields(fields)
        }
        binding.btnRandomizeAll.setOnClickListener { viewModel.randomizeAll() }

        viewModel.featureRows.observe(this) { rows -> featureAdapter.submit(rows) }
        viewModel.experimentalRows.observe(this) { rows -> experimentalAdapter.submit(rows) }

        viewModel.sectionStates.observe(this) { states ->
            suppressSectionToggle = true
            sectionSwitches.forEach { section ->
                section.switch.isChecked = states[section.key] == true
            }
            suppressSectionToggle = false
        }

        viewModel.profile.observe(this) { profile ->
            viewModel.buildFieldKeys.forEach { key ->
                buildInputs[key]?.setText(profile.values.getBuildField(key).orEmpty())
            }
            viewModel.idsFieldKeys.forEach { key ->
                idsInputs[key]?.setText(profile.values.idsFields[key].orEmpty())
            }
        }

        viewModel.tabs.observe(this) { tabs -> renderProfileTabs(tabs) }
        viewModel.activeTabIndex.observe(this) { index ->
            suppressTabSelect = true
            updateTabChipStyles(index)
            suppressTabSelect = false
        }

        viewModel.saveMessage.observe(this) { message ->
            if (message.isNullOrBlank()) return@observe
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeSaveMessage()
        }
    }

    private fun bindDebugLogRow() {
        binding.rowDebugLog.findViewById<android.widget.TextView>(R.id.text_title)
            .setText(R.string.debug_ui_log_title)
        binding.rowDebugLog.findViewById<android.widget.TextView>(R.id.text_desc)
            .setText(R.string.debug_ui_log_desc)
        val switch = binding.rowDebugLog.findViewById<MaterialSwitch>(R.id.switch_feature)
        suppressDebugToggle = true
        switch.isChecked = ConfigDebugLog.isEnabled(this)
        suppressDebugToggle = false
        switch.setOnCheckedChangeListener { _, checked ->
            if (suppressDebugToggle) return@setOnCheckedChangeListener
            ConfigDebugLog.setEnabled(this, checked)
        }
    }

    private fun renderProfileTabs(tabs: List<com.yumito.yumyhook.model.StoredProfile>) {
        binding.profileTabsContainer.removeAllViews()
        val gap = 8.dp()
        tabs.forEachIndexed { index, profile ->
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = profile.name
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                cornerRadius = 20.dp()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    40.dp(),
                ).apply { marginEnd = gap }
                setOnClickListener {
                    if (suppressTabSelect) return@setOnClickListener
                    viewModel.selectTab(index, collectBuildFields(), collectIdsFields())
                }
            }
            binding.profileTabsContainer.addView(btn)
        }
        binding.btnDeleteProfile.isEnabled = tabs.size > 1
        updateTabChipStyles(viewModel.activeTabIndex.value ?: 0)
    }

    private fun updateTabChipStyles(selectedIndex: Int) {
        if (binding.profileTabsContainer.childCount == 0) return
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, Color.GRAY)
        for (i in 0 until binding.profileTabsContainer.childCount) {
            val btn = binding.profileTabsContainer.getChildAt(i) as MaterialButton
            val selected = i == selectedIndex
            if (selected) {
                btn.backgroundTintList = ColorStateList.valueOf(primary)
                btn.setTextColor(onPrimary)
                btn.strokeWidth = 0
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                btn.setTextColor(onSurface)
                btn.strokeWidth = 1.dp()
                btn.strokeColor = ColorStateList.valueOf(outline)
            }
        }
        val safeIndex = selectedIndex.coerceIn(0, binding.profileTabsContainer.childCount - 1)
        binding.profileTabsContainer.getChildAt(safeIndex)?.let { chip ->
            binding.scrollProfileTabs.post {
                val scrollX = (chip.left - binding.scrollProfileTabs.width / 3).coerceAtLeast(0)
                binding.scrollProfileTabs.smoothScrollTo(scrollX, 0)
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showAddProfileDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.config_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_profile)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.addProfile(input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteActiveProfile() {
        if (!viewModel.deleteActiveProfile()) {
            Toast.makeText(this, R.string.cannot_delete_last_profile, Toast.LENGTH_SHORT).show()
        }
    }

    private fun section(key: String, root: View): SectionSwitch {
        return SectionSwitch(key, root, root.findViewById(R.id.switch_feature))
    }

    private fun bindSectionLabels() {
        val labels = HookFeatures.uiCatalog().associateBy { it.key }
        sectionSwitches.forEach { section ->
            val meta = labels[section.key] ?: return@forEach
            section.root.findViewById<android.widget.TextView>(R.id.text_title).text = meta.title
            section.root.findViewById<android.widget.TextView>(R.id.text_desc).text = meta.description
        }
    }

    private fun buildIdsFieldInputs() {
        addFieldInputs(binding.containerIdsFields, viewModel.idsFieldKeys, idsInputs)
    }

    private fun buildBuildFieldInputs() {
        addFieldInputs(binding.containerBuildFields, viewModel.buildFieldKeys, buildInputs)
    }

    private fun addFieldInputs(
        container: android.widget.LinearLayout,
        keys: List<String>,
        target: MutableMap<String, TextInputEditText>,
    ) {
        keys.forEach { key ->
            val layout = TextInputLayout(this).apply {
                hint = key
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            }
            val edit = TextInputEditText(layout.context)
            layout.addView(edit)
            container.addView(layout)
            target[key] = edit
        }
    }

    private fun logFeatureToggle(key: String, enabled: Boolean) {
        val title = (HookFeatures.uiCatalog() + HookFeatures.experimentalCatalog())
            .find { it.key == key }
            ?.title ?: key
        ConfigDebugLog.logFeatureToggle(this, title, key, enabled)
    }

    private fun collectBuildFields(): Map<String, String> =
        buildInputs.mapValues { (_, edit) -> edit.text?.toString().orEmpty() }

    private fun collectIdsFields(): Map<String, String> =
        idsInputs.mapValues { (_, edit) -> edit.text?.toString().orEmpty() }
}
