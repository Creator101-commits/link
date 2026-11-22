package com.link.clipboardsync

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : AppCompatActivity(), NetworkSyncClient.SyncListener, ClipboardSyncManager.HistoryListener, LinkNotificationManager.SettingsListener, LinkFileTransferManager.TransferUiListener {

    companion object {
        const val TAB_HOME = 0
        const val TAB_FILES = 1
        const val TAB_SETTINGS = 2
        /** True while MainActivity is resumed — lets share/hotspot features
         *  launch their intents directly instead of via a notification. */
        @Volatile var isResumed = false
            private set
    }

    // Header views
    private lateinit var dotStatus: View
    private lateinit var tvStatusPill: TextView
    private lateinit var tvLargeTitle: TextView
    private var dotBlinkAnimator: ObjectAnimator? = null

    // Setup Progress Indicator
    private lateinit var layoutFinishSetupCard: LinearLayout
    private lateinit var layoutSetupProgress: LinearLayout
    private lateinit var tvSetupProgressStatus: TextView
    private lateinit var tvSetupProgressDetail: TextView

    // Service notice (Accessibility)
    private lateinit var layoutServiceNotice: LinearLayout
    private lateinit var btnEnableAccessibility: Button

    // Service notice (Notification Listener)
    private lateinit var layoutNotificationNotice: LinearLayout
    private lateinit var btnEnableNotificationAccess: Button

    // Phone access notice (calls + signal on Mac)
    private lateinit var layoutPhoneAccessNotice: LinearLayout
    private lateinit var btnEnablePhoneAccess: Button

    // Screen A: Connected + Bottom Nav Tabs
    private lateinit var layoutScreenConnected: LinearLayout
    private lateinit var layoutTabLink: LinearLayout
    private lateinit var layoutTabFiles: LinearLayout
    private lateinit var layoutTabSettings: LinearLayout
    private lateinit var layoutBottomNav: FrameLayout
    private lateinit var viewNavActiveIndicator: View
    private lateinit var navTabHome: LinearLayout
    private lateinit var ivNavHome: ImageView
    private lateinit var tvNavHome: TextView
    private lateinit var navTabFiles: LinearLayout
    private lateinit var ivNavFiles: ImageView
    private lateinit var tvNavFiles: TextView
    private lateinit var navTabSettings: LinearLayout
    private lateinit var ivNavSettings: ImageView
    private lateinit var tvNavSettings: TextView
    private var currentTab: Int = TAB_HOME
    private var isUpdatingSettingsUi = false

    // Tab 1: Home Overview
    private lateinit var heroCard: LinearLayout
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroSub: TextView
    private lateinit var linkViewHero: ImageView
    private lateinit var layoutHeroCautionBanner: LinearLayout
    private lateinit var dotHeroCaution: View
    private lateinit var tvHeroCautionTitle: TextView
    private lateinit var btnTroubleshootToggle: TextView
    private lateinit var tvHeroCautionText: TextView
    private lateinit var layoutTroubleshootDetails: LinearLayout
    private lateinit var tvDevicePhoneName: TextView
    private lateinit var tvDeviceMacName: TextView
    private lateinit var tvLiveBadge: TextView
    private lateinit var btnHeroConnect: Button

    // Tab 2: Files Page
    private lateinit var tvFilesTargetMac: TextView
    private lateinit var tvFilesConnectionHint: TextView
    private lateinit var btnSelectFilesToSend: Button
    private lateinit var btnSelectFolderToSend: Button
    private lateinit var btnClearCompletedTransfers: TextView
    private lateinit var layoutTransfersEmptyState: LinearLayout
    private lateinit var layoutTransfersList: LinearLayout

    // Tab 3: Settings Page
    private lateinit var switchNotifMaster: SwitchCompat
    private lateinit var tvNotifMasterPausedNotice: TextView
    private lateinit var layoutNotifSubOptions: LinearLayout
    private lateinit var switchAndroidNotif: SwitchCompat
    private lateinit var switchMacNotif: SwitchCompat
    private lateinit var rowPhoneVibrate: LinearLayout
    private lateinit var switchPhoneVibrate: SwitchCompat
    private lateinit var switchAutoAcceptFiles: SwitchCompat
    private lateinit var switchFastLink: SwitchCompat
    private lateinit var switchSkipSilent: SwitchCompat
    private lateinit var rowSaveLocation: LinearLayout
    private lateinit var tvSaveLocationPath: TextView
    private lateinit var btnResetSaveLocation: TextView
    private lateinit var btnChangeSaveLocation: TextView
    private lateinit var rowPersonalHotspot: LinearLayout
    private lateinit var tvHotspotName: TextView
    private lateinit var rowSettingNotifAccess: LinearLayout
    private lateinit var tvSettingNotifAccessStatus: TextView
    private lateinit var rowSettingAccessibility: LinearLayout
    private lateinit var tvSettingAccessibilityStatus: TextView
    private lateinit var tvSettingsDeviceMacName: TextView
    private lateinit var tvSettingsDeviceStatus: TextView
    private lateinit var btnToggleConnectMacSettings: Button
    private lateinit var btnUnpairMacSettings: Button

    // Failure / Retry tracking
    private var consecutiveFailures = 0
    private var userConnectAttempts = 0
    private var wasConnectedBefore = false
    private var userManuallyDisconnected = false

    // Screen B: Pairing
    private lateinit var layoutScreenPairing: LinearLayout
    private lateinit var linkViewPairing: ImageView
    private lateinit var tvPairingTitle: TextView
    private lateinit var tvPairingSub: TextView
    private lateinit var layoutNoteBanner: LinearLayout
    private lateinit var dotNote: View
    private lateinit var tvNoteText: TextView
    private lateinit var btnScanQr: Button
    private lateinit var etPairCode: EditText
    private val codeBoxes = ArrayList<TextView>()
    private lateinit var tvToggleManualIp: TextView
    private lateinit var layoutManualIp: LinearLayout
    private lateinit var etServerIp: EditText
    private lateinit var etServerPort: EditText
    private lateinit var btnPair: Button

    // Colors
    private val colorOnAccent by lazy { ContextCompat.getColor(this, R.color.on_accent) }
    private val colorAccent by lazy { ContextCompat.getColor(this, R.color.accent) }
    private val colorRed by lazy { ContextCompat.getColor(this, R.color.red) }
    private val colorTertiaryLabel by lazy { ContextCompat.getColor(this, R.color.tertiary_label) }
    private val colorSecondaryLabel by lazy { ContextCompat.getColor(this, R.color.secondary_label) }
    private val colorLabel by lazy { ContextCompat.getColor(this, R.color.label) }

    // Debug-only screenshot/demo state (BuildConfig.DEBUG + intent extra "demo_state")
    private var demoMode = false
    private var demoConnected = false
    private var pendingDemoState: String? = null

    // QR Code Scanner Launcher
    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            handleScannedQr(result.contents)
        }
    }

    // Multiple File Picker Launcher
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            LinkFileTransferManager.sendFiles(this, uris)
        }
    }

    // Folder Picker Launcher (SAF Tree Picker)
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            LinkFileTransferManager.sendFolder(this, uri)
        }
    }

    // Preferred Save Location Folder Launcher (SAF Tree Picker)
    private val chooseSaveDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                val doc = DocumentFile.fromTreeUri(this, uri)
                val displayName = doc?.name ?: run {
                    val path = uri.path ?: ""
                    val segs = path.split(":")
                    if (segs.size > 1) segs[1] else "Custom Folder"
                }
                LinkPrefs.setCustomSaveLocation(this, uri.toString(), displayName)
                updateSettingsUI()
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed taking persistable URI permission: ${e.message}")
            }
        }
    }

    private fun isLiveConnected(): Boolean {
        return demoConnected || ClipboardSyncManager.networkClient?.isConnected() == true
    }

    // Debug demo mode keeps pairing purely in-memory so real prefs are never clobbered.
    private fun uiPaired(): Boolean = if (demoMode) demoConnected else LinkPrefs.isPaired(this)
    private fun uiMacName(): String = if (demoMode) "MacBook Pro" else (LinkPrefs.macName(this) ?: "Mac")

    private fun safeGetFont(resId: Int, fallback: Typeface = Typeface.DEFAULT): Typeface {
        return try {
            ResourcesCompat.getFont(this, resId) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (BuildConfig.DEBUG) {
            pendingDemoState = intent.getStringExtra("demo_state")
            demoMode = pendingDemoState != null
            demoConnected = pendingDemoState != null && pendingDemoState != "pairing"
        }

        initViews()
        setupListeners()

        if (!demoMode) {
            checkPermissions()
        }

        // Handle navigation bar and gesture insets dynamically so bottom nav and content never overlap
        findViewById<View>(R.id.rootLayout)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val navInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val density = resources.displayMetrics.density

                val navLp = layoutBottomNav.layoutParams as? FrameLayout.LayoutParams
                if (navLp != null) {
                    navLp.bottomMargin = (18 * density).toInt() + navInsets.bottom
                    layoutBottomNav.layoutParams = navLp
                }

                val extraBottom = (62 * density).toInt() + (18 * density).toInt() + (16 * density).toInt() + navInsets.bottom
                findViewById<View>(R.id.layoutScrollContent)?.let { content ->
                    content.setPadding(
                        content.paddingLeft,
                        content.paddingTop,
                        content.paddingRight,
                        extraBottom
                    )
                }
                insets
            }
        }

        ClipboardSyncManager.init(this)
        LinkNotificationManager.init(this)
        if (!demoMode) {
            ClipboardSyncForegroundService.start(this)
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val openTab = intent.getStringExtra("open_tab")
        if (openTab == "files" || openTab == "transfer") {
            selectTab(TAB_FILES)
            return
        }
        if (openTab == "settings") {
            selectTab(TAB_SETTINGS)
            return
        }
        val action = intent.action ?: return
        if (action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
            if (streamUri != null) {
                selectTab(TAB_FILES)
                LinkFileTransferManager.sendFiles(this, listOf(streamUri))
                intent.action = Intent.ACTION_MAIN
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val streamUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?: intent.clipData?.let { clip ->
                    val list = ArrayList<Uri>()
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { list.add(it) }
                    }
                    list
                }
            if (!streamUris.isNullOrEmpty()) {
                selectTab(TAB_FILES)
                LinkFileTransferManager.sendFiles(this, streamUris)
                intent.action = Intent.ACTION_MAIN
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        ClipboardSyncManager.init(this)
        ClipboardSyncManager.addHistoryListener(this)
        ClipboardSyncManager.networkClient?.addListener(this)
        LinkNotificationManager.addSettingsListener(this)
        LinkFileTransferManager.addListener(this)

        updateAccessibilityNotice()
        updateSetupProgress()
        renderTransfersList()
        updateUI()

        if (demoMode) {
            applyDemoState()
        } else if (!uiPaired()) {
            ClipboardSyncManager.networkClient?.startDiscovery()
        } else if (missingBluetoothPermissions().isNotEmpty()) {
            ensureBluetoothPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        ClipboardSyncManager.networkClient?.removeListener(this)
        ClipboardSyncManager.removeHistoryListener(this)
        LinkNotificationManager.removeSettingsListener(this)
        LinkFileTransferManager.removeListener(this)
    }

    private fun initViews() {
        // Header
        dotStatus = findViewById(R.id.dotStatus)
        tvStatusPill = findViewById(R.id.tvStatusPill)
        tvLargeTitle = findViewById(R.id.tvLargeTitle)

        // Setup Progress
        layoutFinishSetupCard = findViewById(R.id.layoutFinishSetupCard)
        layoutSetupProgress = findViewById(R.id.layoutSetupProgress)
        tvSetupProgressStatus = findViewById(R.id.tvSetupProgressStatus)
        tvSetupProgressDetail = findViewById(R.id.tvSetupProgressDetail)

        // Accessibility service notice
        layoutServiceNotice = findViewById(R.id.layoutServiceNotice)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)

        // Notification Access notice
        layoutNotificationNotice = findViewById(R.id.layoutNotificationNotice)
        btnEnableNotificationAccess = findViewById(R.id.btnEnableNotificationAccess)

        // Phone access notice
        layoutPhoneAccessNotice = findViewById(R.id.layoutPhoneAccessNotice)
        btnEnablePhoneAccess = findViewById(R.id.btnEnablePhoneAccess)

        // Screen A & Bottom Nav Tabs
        layoutScreenConnected = findViewById(R.id.layoutScreenConnected)
        layoutTabLink = findViewById(R.id.layoutTabLink)
        layoutTabFiles = findViewById(R.id.layoutTabFiles)
        layoutTabSettings = findViewById(R.id.layoutTabSettings)
        layoutBottomNav = findViewById(R.id.layoutBottomNav)
        findViewById<GlassTabBarView>(R.id.layoutBottomNav)
            ?.setBlurSource(findViewById(R.id.mainScrollView))
        viewNavActiveIndicator = findViewById(R.id.viewNavActiveIndicator)
        navTabHome = findViewById(R.id.navTabHome)
        ivNavHome = findViewById(R.id.ivNavHome)
        tvNavHome = findViewById(R.id.tvNavHome)
        navTabFiles = findViewById(R.id.navTabFiles)
        ivNavFiles = findViewById(R.id.ivNavFiles)
        tvNavFiles = findViewById(R.id.tvNavFiles)
        navTabSettings = findViewById(R.id.navTabSettings)
        ivNavSettings = findViewById(R.id.ivNavSettings)
        tvNavSettings = findViewById(R.id.tvNavSettings)

        // Tab 1: Home Overview
        heroCard = findViewById(R.id.heroCard)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroSub = findViewById(R.id.tvHeroSub)
        linkViewHero = findViewById(R.id.linkViewHero)
        layoutHeroCautionBanner = findViewById(R.id.layoutHeroCautionBanner)
        dotHeroCaution = findViewById(R.id.dotHeroCaution)
        tvHeroCautionTitle = findViewById(R.id.tvHeroCautionTitle)
        btnTroubleshootToggle = findViewById(R.id.btnTroubleshootToggle)
        tvHeroCautionText = findViewById(R.id.tvHeroCautionText)
        layoutTroubleshootDetails = findViewById(R.id.layoutTroubleshootDetails)
        tvDevicePhoneName = findViewById(R.id.tvDevicePhoneName)
        tvDeviceMacName = findViewById(R.id.tvDeviceMacName)
        tvLiveBadge = findViewById(R.id.tvLiveBadge)
        btnHeroConnect = findViewById(R.id.btnHeroConnect)

        // Tab 2: Files
        tvFilesTargetMac = findViewById(R.id.tvFilesTargetMac)
        tvFilesConnectionHint = findViewById(R.id.tvFilesConnectionHint)
        btnSelectFilesToSend = findViewById(R.id.btnSelectFilesToSend)
        btnSelectFolderToSend = findViewById(R.id.btnSelectFolderToSend)
        btnClearCompletedTransfers = findViewById(R.id.btnClearCompletedTransfers)
        layoutTransfersEmptyState = findViewById(R.id.layoutTransfersEmptyState)
        layoutTransfersList = findViewById(R.id.layoutTransfersList)

        // Tab 3: Settings
        switchNotifMaster = findViewById(R.id.switchNotifMaster)
        tvNotifMasterPausedNotice = findViewById(R.id.tvNotifMasterPausedNotice)
        layoutNotifSubOptions = findViewById(R.id.layoutNotifSubOptions)
        switchAndroidNotif = findViewById(R.id.switchAndroidNotif)
        switchMacNotif = findViewById(R.id.switchMacNotif)
        rowPhoneVibrate = findViewById(R.id.rowPhoneVibrate)
        switchPhoneVibrate = findViewById(R.id.switchPhoneVibrate)
        rowSaveLocation = findViewById(R.id.rowSaveLocation)
        tvSaveLocationPath = findViewById(R.id.tvSaveLocationPath)
        btnResetSaveLocation = findViewById(R.id.btnResetSaveLocation)
        btnChangeSaveLocation = findViewById(R.id.btnChangeSaveLocation)
        rowPersonalHotspot = findViewById(R.id.rowPersonalHotspot)
        tvHotspotName = findViewById(R.id.tvHotspotName)
        switchAutoAcceptFiles = findViewById(R.id.switchAutoAcceptFiles)
        switchFastLink = findViewById(R.id.switchFastLink)
        switchSkipSilent = findViewById(R.id.switchSkipSilent)
        rowSettingNotifAccess = findViewById(R.id.rowSettingNotifAccess)
        tvSettingNotifAccessStatus = findViewById(R.id.tvSettingNotifAccessStatus)
        rowSettingAccessibility = findViewById(R.id.rowSettingAccessibility)
        tvSettingAccessibilityStatus = findViewById(R.id.tvSettingAccessibilityStatus)
        tvSettingsDeviceMacName = findViewById(R.id.tvSettingsDeviceMacName)
        tvSettingsDeviceStatus = findViewById(R.id.tvSettingsDeviceStatus)
        btnToggleConnectMacSettings = findViewById(R.id.btnToggleConnectMacSettings)
        btnUnpairMacSettings = findViewById(R.id.btnUnpairMacSettings)

        styleLinkSwitch(switchNotifMaster)
        styleLinkSwitch(switchAndroidNotif)
        styleLinkSwitch(switchMacNotif)
        styleLinkSwitch(switchPhoneVibrate)
        styleLinkSwitch(switchAutoAcceptFiles)
        styleLinkSwitch(switchFastLink)
        styleLinkSwitch(switchSkipSilent)

        // Screen B
        layoutScreenPairing = findViewById(R.id.layoutScreenPairing)
        linkViewPairing = findViewById(R.id.linkViewPairing)
        tvPairingTitle = findViewById(R.id.tvPairingTitle)
        tvPairingSub = findViewById(R.id.tvPairingSub)
        layoutNoteBanner = findViewById(R.id.layoutNoteBanner)
        dotNote = findViewById(R.id.dotNote)
        tvNoteText = findViewById(R.id.tvNoteText)
        btnScanQr = findViewById(R.id.btnScanQr)
        etPairCode = findViewById(R.id.etPairCode)

        codeBoxes.clear()
        codeBoxes.add(findViewById(R.id.tvCode0))
        codeBoxes.add(findViewById(R.id.tvCode1))
        codeBoxes.add(findViewById(R.id.tvCode2))
        codeBoxes.add(findViewById(R.id.tvCode3))
        codeBoxes.add(findViewById(R.id.tvCode4))
        codeBoxes.add(findViewById(R.id.tvCode5))

        tvToggleManualIp = findViewById(R.id.tvToggleManualIp)
        layoutManualIp = findViewById(R.id.layoutManualIp)
        etServerIp = findViewById(R.id.etServerIp)
        etServerPort = findViewById(R.id.etServerPort)
        btnPair = findViewById(R.id.btnPair)

        // Real Phone Model Name (e.g., Galaxy A52s)
        val phoneModel = Build.MODEL.let {
            if (it.startsWith(Build.MANUFACTURER, ignoreCase = true)) it
            else "${Build.MANUFACTURER.replaceFirstChar { c -> c.uppercase() }} $it"
        }
        tvDevicePhoneName.text = if (demoMode) "Pixel 8" else phoneModel

        // Prefill server IP if saved
        LinkPrefs.host(this)?.let { etServerIp.setText(it) }
        LinkPrefs.port(this).let { etServerPort.setText(it.toString()) }
    }

    private fun styleLinkSwitch(sw: SwitchCompat) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        // Apple-style switch: white thumb, accent track on / neutral track off.
        val thumbColors = intArrayOf(
            colorOnAccent,
            colorOnAccent
        )
        val trackColors = intArrayOf(
            ContextCompat.getColor(this, R.color.accent),
            ContextCompat.getColor(this, R.color.card_pressed)
        )
        sw.thumbTintList = ColorStateList(states, thumbColors)
        sw.trackTintList = ColorStateList(states, trackColors)
    }

    private var navPillAnimator: AnimatorSet? = null

    private fun selectTab(tabIndex: Int) {
        if (currentTab == tabIndex) return
        currentTab = tabIndex

        val fontSemibold = safeGetFont(R.font.sf_pro_text_semibold, Typeface.DEFAULT_BOLD)
        val fontMedium = safeGetFont(R.font.sf_pro_text_medium, Typeface.DEFAULT)

        // 1. Update tab icon and label styles for all 3 tabs
        val homeActive = tabIndex == TAB_HOME
        ivNavHome.setImageResource(if (homeActive) R.drawable.ic_nav_home_filled else R.drawable.ic_nav_home_outline)
        ivNavHome.imageTintList = ColorStateList.valueOf(if (homeActive) colorAccent else colorSecondaryLabel)
        tvNavHome.setTextColor(if (homeActive) colorAccent else colorSecondaryLabel)
        tvNavHome.typeface = if (homeActive) fontSemibold else fontMedium

        val filesActive = tabIndex == TAB_FILES
        ivNavFiles.setImageResource(if (filesActive) R.drawable.ic_nav_transfer_filled else R.drawable.ic_nav_transfer_outline)
        ivNavFiles.imageTintList = ColorStateList.valueOf(if (filesActive) colorAccent else colorSecondaryLabel)
        tvNavFiles.setTextColor(if (filesActive) colorAccent else colorSecondaryLabel)
        tvNavFiles.typeface = if (filesActive) fontSemibold else fontMedium

        val settingsActive = tabIndex == TAB_SETTINGS
        ivNavSettings.setImageResource(if (settingsActive) R.drawable.ic_nav_settings_filled else R.drawable.ic_nav_settings_outline)
        ivNavSettings.imageTintList = ColorStateList.valueOf(if (settingsActive) colorAccent else colorSecondaryLabel)
        tvNavSettings.setTextColor(if (settingsActive) colorAccent else colorSecondaryLabel)
        tvNavSettings.typeface = if (settingsActive) fontSemibold else fontMedium

        tvLargeTitle.text = when (tabIndex) {
            TAB_FILES -> "Files"
            TAB_SETTINGS -> "Settings"
            else -> "Link"
        }

        // 2. Animate the sliding indicator pill across 3 tabs
        navPillAnimator?.cancel()
        // Before first layout navTabHome.width is 0 (e.g. cold start with
        // open_tab=settings) — targetX would collapse to ~0 and the pill would
        // stick under Home. Defer to after layout and set it directly.
        if (navTabHome.width == 0) {
            // navTabHome is the width source — when IT lays out its width is
            // final, so position the pill then (indicator may lay out earlier).
            navTabHome.doOnLayout {
                val sw = navTabHome.width.toFloat().coerceAtLeast(1f)
                val iw = viewNavActiveIndicator.width.toFloat().coerceAtLeast(1f)
                viewNavActiveIndicator.translationX = sw * tabIndex + (sw - iw) / 2f
            }
        } else {
            val segmentWidth = navTabHome.width.toFloat()
            val indicatorWidth = viewNavActiveIndicator.width.toFloat().coerceAtLeast(1f)
            val targetX = segmentWidth * tabIndex + (segmentWidth - indicatorWidth) / 2f

            val slideAnim = ObjectAnimator.ofFloat(
                viewNavActiveIndicator,
                "translationX",
                viewNavActiveIndicator.translationX,
                targetX
            ).apply {
                duration = 320
                interpolator = OvershootInterpolator(0.95f)
            }
            val stretchXAnim = ObjectAnimator.ofFloat(
                viewNavActiveIndicator,
                "scaleX",
                1.0f, 1.15f, 0.97f, 1.0f
            ).apply {
                duration = 320
                interpolator = AccelerateDecelerateInterpolator()
            }
            val squashYAnim = ObjectAnimator.ofFloat(
                viewNavActiveIndicator,
                "scaleY",
                1.0f, 0.92f, 1.02f, 1.0f
            ).apply {
                duration = 320
                interpolator = AccelerateDecelerateInterpolator()
            }
            navPillAnimator = AnimatorSet().apply {
                playTogether(slideAnim, stretchXAnim, squashYAnim)
                start()
            }
        }

        // 3. Subtle spring pop on the newly selected tab icon
        val activeIcon = when (tabIndex) {
            TAB_FILES -> ivNavFiles
            TAB_SETTINGS -> ivNavSettings
            else -> ivNavHome
        }
        activeIcon.scaleX = 0.82f
        activeIcon.scaleY = 0.82f
        activeIcon.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()

        updateAccessibilityNotice()
        updateSetupProgress()
        updateSettingsUI()
        renderTransfersList()
        updateUI()

        // 4. Smooth cross-fade & slight slide on the newly visible tab page
        val targetPage = when {
            tabIndex == TAB_SETTINGS -> layoutTabSettings
            tabIndex == TAB_FILES -> layoutTabFiles
            uiPaired() -> layoutScreenConnected
            else -> layoutScreenPairing
        }
        targetPage.alpha = 0.35f
        targetPage.translationY = 8f * resources.displayMetrics.density
        targetPage.animate()
            .alpha(1.0f)
            .translationY(0f)
            .setDuration(210)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun setupListeners() {
        navTabHome.setOnClickListener {
            selectTab(TAB_HOME)
        }

        navTabFiles.setOnClickListener {
            selectTab(TAB_FILES)
        }

        navTabSettings.setOnClickListener {
            selectTab(TAB_SETTINGS)
        }

        btnSelectFilesToSend.setOnClickListener {
            if (isLiveConnected() != true) {
                Toast.makeText(this, "Connect to your Mac first to send files.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        btnSelectFolderToSend.setOnClickListener {
            if (isLiveConnected() != true) {
                Toast.makeText(this, "Connect to your Mac first to send folders.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            folderPickerLauncher.launch(null)
        }

        btnClearCompletedTransfers.setOnClickListener {
            LinkFileTransferManager.clearCompletedTransfers()
        }

        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnEnableNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnEnablePhoneAccess.setOnClickListener {
            requestPhonePermissions()
        }

        rowSettingNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        rowSettingAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnTroubleshootToggle.setOnClickListener {
            if (layoutTroubleshootDetails.visibility == View.VISIBLE) {
                layoutTroubleshootDetails.visibility = View.GONE
                btnTroubleshootToggle.text = "Details"
            } else {
                layoutTroubleshootDetails.visibility = View.VISIBLE
                btnTroubleshootToggle.text = "Hide"
            }
        }

        switchNotifMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSettingsUi) return@setOnCheckedChangeListener
            LinkPrefs.setNotifMasterEnabled(this, isChecked, updateTimestamp = true)
            if (!isChecked) {
                LinkNotificationManager.cancelAllMacAlerts(this)
            } else if (LinkPrefs.isAndroidNotifEnabled(this)) {
                LinkNotificationListenerService.instance?.syncActiveNotifications()
            }
            LinkNotificationManager.sendSettingsToMac(this)
            updateAccessibilityNotice()
            updateSetupProgress()
            updateSettingsUI()
            updateUI()
        }

        switchAndroidNotif.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSettingsUi) return@setOnCheckedChangeListener
            LinkPrefs.setAndroidNotifEnabled(this, isChecked, updateTimestamp = true)
            if (isChecked && LinkPrefs.isNotifMasterEnabled(this)) {
                LinkNotificationListenerService.instance?.syncActiveNotifications()
            }
            LinkNotificationManager.sendSettingsToMac(this)
            updateAccessibilityNotice()
            updateSetupProgress()
            updateSettingsUI()
            updateUI()
        }

        switchMacNotif.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSettingsUi) return@setOnCheckedChangeListener
            LinkPrefs.setMacNotifEnabled(this, isChecked, updateTimestamp = true)
            if (!isChecked) {
                LinkNotificationManager.cancelAllMacAlerts(this)
            }
            LinkNotificationManager.sendSettingsToMac(this)
            updateSettingsUI()
            updateUI()
        }

        switchPhoneVibrate.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSettingsUi) return@setOnCheckedChangeListener
            LinkPrefs.setPhoneVibrateEnabled(this, isChecked)
            updateSettingsUI()
        }

        btnChangeSaveLocation.setOnClickListener {
            chooseSaveDirLauncher.launch(null)
        }

        rowSaveLocation.setOnClickListener {
            chooseSaveDirLauncher.launch(null)
        }

        rowPersonalHotspot.setOnClickListener {
            showHotspotDialog()
        }

        btnResetSaveLocation.setOnClickListener {
            LinkPrefs.setCustomSaveLocation(this, null, null)
            updateSettingsUI()
        }

        switchAutoAcceptFiles.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSettingsUi) return@setOnCheckedChangeListener
            LinkPrefs.setAutoAcceptFilesEnabled(this, isChecked, updateTimestamp = true)
            LinkNotificationManager.sendSettingsToMac(this)
            updateSettingsUI()
        }

        switchFastLink.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSettingsUi) return@setOnCheckedChangeListener
            LinkPrefs.setFastLinkEnabled(this, isChecked)
            if (isChecked) {
                FastLink.clearFailureCooldown()
                ensureBluetoothPermissions()
            }
        }

        switchSkipSilent.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSettingsUi) return@setOnCheckedChangeListener
            LinkPrefs.setSkipSilentNotificationsEnabled(this, isChecked, updateTimestamp = true)
            LinkNotificationManager.sendSettingsToMac(this)
            updateSettingsUI()
        }

        btnScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Point camera at the QR code in the Mac menu bar")
                setBeepEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(PortraitCaptureActivity::class.java)
                setBarcodeImageEnabled(false)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            }
            qrScannerLauncher.launch(options)
        }

        tvToggleManualIp.setOnClickListener {
            if (layoutManualIp.visibility == View.VISIBLE) {
                layoutManualIp.visibility = View.GONE
                tvToggleManualIp.text = "Enter IP address instead"
            } else {
                layoutManualIp.visibility = View.VISIBLE
                tvToggleManualIp.text = "Hide IP address setup"
            }
        }

        etPairCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val clean = s?.toString()?.replace(" ", "")?.replace("-", "") ?: ""
                updateCodeBoxes(clean)
                val isReady = clean.length == 6
                btnPair.isEnabled = isReady
                btnPair.alpha = if (isReady) 1.0f else 0.45f

                if (isReady && btnPair.isEnabled && !isConnectingState) {
                    btnPair.performClick()
                }
            }
        })

        // Clicking on any box focuses the hidden EditText
        val focusInputListener = View.OnClickListener {
            etPairCode.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(etPairCode, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        findViewById<View>(R.id.layoutCodeBoxes).setOnClickListener(focusInputListener)
        for (box in codeBoxes) {
            box.setOnClickListener(focusInputListener)
        }

        btnPair.setOnClickListener {
            ensureBluetoothPermissions()
            val rawCode = etPairCode.text.toString().trim()
            val code = rawCode.replace(" ", "").replace("-", "")
            if (code.length != 6) {
                showPairingNote("Please enter a 6-digit code.", BannerStyle.ERROR)
                return@setOnClickListener
            }

            userConnectAttempts++
            val localNet = isLocalNetworkAvailable(this)

            var host = etServerIp.text.toString().trim()
            if (host.isEmpty()) {
                host = LinkPrefs.host(this) ?: ""
            }
            val port = etServerPort.text.toString().toIntOrNull() ?: NetworkSyncClient.DEFAULT_PORT

            setConnectingState(true)
            val isCaution = !localNet || userConnectAttempts >= 2 || consecutiveFailures >= 2
            val noteMsg = if (isCaution) {
                connectionErrorNote()
            } else {
                "Looking for your Mac nearby…"
            }
            showPairingNote(noteMsg, if (isCaution) BannerStyle.ERROR else BannerStyle.CONNECTING)

            val hostParam = host.ifEmpty { null }
            ClipboardSyncManager.pair(this, hostParam, port, code)
        }

        btnHeroConnect.setOnClickListener {
            connectSavedMac()
        }

        btnToggleConnectMacSettings.setOnClickListener {
            if (!uiPaired()) return@setOnClickListener
            if (isLiveConnected() == true) {
                userManuallyDisconnected = true
                consecutiveFailures = 0
                userConnectAttempts = 0
                wasConnectedBefore = false
                isConnectingState = false
                ClipboardSyncManager.disconnect(this)
                updateSettingsUI()
                updateUI()
            } else {
                connectSavedMac()
            }
        }

        btnUnpairMacSettings.setOnClickListener {
            showUnpairConfirmationDialog()
        }
    }

    private fun connectSavedMac() {
        userManuallyDisconnected = false
        userConnectAttempts++
        if (!isLocalNetworkAvailable(this)) {
            layoutHeroCautionBanner.visibility = View.VISIBLE
            tvHeroCautionTitle.text = "Couldn't connect to your Mac"
            tvHeroCautionText.text = if (noTransportAvailable()) {
                "Turn on Bluetooth to connect without Wi-Fi."
            } else {
                "Link couldn't reach your Mac. Please verify the following:"
            }
        }

        setConnectingState(true)
        updateSettingsUI()
        ClipboardSyncManager.reconnect(this)
    }

    private fun showUnpairConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_unpair_title)
            .setMessage(R.string.dialog_unpair_message)
            .setPositiveButton(R.string.dialog_unpair) { _, _ ->
                userManuallyDisconnected = false
                ClipboardSyncManager.unpair(this)
                etPairCode.setText("")
                etServerIp.setText("")
                consecutiveFailures = 0
                userConnectAttempts = 0
                wasConnectedBefore = false
                layoutNoteBanner.visibility = View.GONE
                setConnectingState(false)
                updateSettingsUI()
                updateUI()
                Toast.makeText(this, "Unpaired successfully", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private var isConnectingState = false

    private fun updateCodeBoxes(value: String) {
        val activeIndex = value.length.coerceAtMost(5)
        for (i in 0 until 6) {
            val box = codeBoxes[i]
            if (i < value.length) {
                box.text = value[i].toString()
            } else {
                box.text = ""
            }
            if (i == activeIndex && etPairCode.isFocused) {
                box.setBackgroundResource(R.drawable.bg_code_box_active)
            } else {
                box.setBackgroundResource(R.drawable.bg_code_box)
            }
        }
    }

    private fun handleScannedQr(qrData: String) {
        ensureBluetoothPermissions()
        try {
            if (qrData.trim().startsWith("{")) {
                val json = JSONObject(qrData)
                val code = json.optString("code").replace(" ", "").replace("-", "").trim()
                val rawIp = json.optString("ip").trim()
                // Loopback IPs mean the Mac has no LAN; Bluetooth covers that case.
                val ip = if (rawIp.isNotEmpty() && NetworkSyncClient.isUsableLanHost(rawIp)) rawIp else null
                val rawPort = json.optInt("port", NetworkSyncClient.DEFAULT_PORT)
                val port = if (rawPort in 1024..65535) rawPort else NetworkSyncClient.DEFAULT_PORT
                val btId = json.optString("bt").trim()
                    .takeIf { LinkTransport.isValidBluetoothId(it) }

                if (code.length == 6 && code.all { it.isDigit() }) {
                    etPairCode.setText(code)
                    setConnectingState(true)
                    showPairingNote("Looking for your Mac nearby…", BannerStyle.CONNECTING)
                    ClipboardSyncManager.pair(this, ip, port, code, btId)
                    return
                }
            }

            val cleanCode = qrData.replace(" ", "").replace("-", "").trim()
            if (cleanCode.length == 6 && cleanCode.all { it.isDigit() }) {
                etPairCode.setText(cleanCode)
                btnPair.performClick()
                return
            }

            showPairingNote("Unrecognized QR code format.", BannerStyle.ERROR)
        } catch (e: Exception) {
            showPairingNote(connectionErrorNote(), BannerStyle.ERROR)
        }
    }

    private fun setConnectingState(connecting: Boolean) {
        isConnectingState = connecting
        if (connecting) {
            tvStatusPill.text = "Connecting..."
            tvStatusPill.setTextColor(colorAccent)
            dotStatus.backgroundTintList = ColorStateList.valueOf(colorAccent)
            startDotBlink()

            btnPair.isEnabled = false
            btnPair.text = "Connecting…"
            btnHeroConnect.isEnabled = false
            btnHeroConnect.text = "Connecting…"
        } else {
            stopDotBlink()
            btnPair.text = "Connect"
            btnPair.isEnabled = etPairCode.text.toString().replace(" ", "").length == 6
            btnHeroConnect.isEnabled = true
            btnHeroConnect.text = if (wasConnectedBefore) "Try again" else "Connect to Mac"
        }
    }

    private fun startDotBlink() {
        if (dotBlinkAnimator == null) {
            dotBlinkAnimator = ObjectAnimator.ofFloat(dotStatus, "alpha", 1.0f, 0.2f, 1.0f).apply {
                duration = 700
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopDotBlink() {
        dotBlinkAnimator?.cancel()
        dotBlinkAnimator = null
        dotStatus.alpha = 1.0f
    }

    enum class BannerStyle { ERROR, CONNECTING, CONNECTED }

    private fun showPairingNote(message: String, style: BannerStyle) {
        layoutNoteBanner.visibility = View.VISIBLE
        tvNoteText.text = message
        when (style) {
            BannerStyle.ERROR -> {
                layoutNoteBanner.setBackgroundResource(R.drawable.bg_note_error)
                dotNote.backgroundTintList = ColorStateList.valueOf(colorRed)
                tvNoteText.setTextColor(colorRed)
            }
            BannerStyle.CONNECTING -> {
                layoutNoteBanner.setBackgroundResource(R.drawable.bg_note_connecting)
                dotNote.backgroundTintList = ColorStateList.valueOf(colorTertiaryLabel)
                tvNoteText.setTextColor(colorTertiaryLabel)
            }
            BannerStyle.CONNECTED -> {
                layoutNoteBanner.setBackgroundResource(R.drawable.bg_note_connected)
                dotNote.backgroundTintList = ColorStateList.valueOf(colorAccent)
                tvNoteText.setTextColor(colorAccent)
            }
        }
    }

    private fun refreshFinishSetupCard() {
        layoutFinishSetupCard.visibility =
            if (layoutSetupProgress.visibility == View.VISIBLE ||
                layoutServiceNotice.visibility == View.VISIBLE ||
                layoutNotificationNotice.visibility == View.VISIBLE ||
                layoutPhoneAccessNotice.visibility == View.VISIBLE
            ) View.VISIBLE else View.GONE
    }

    private fun updateSetupProgress() {
        if (currentTab != TAB_HOME) {
            layoutSetupProgress.visibility = View.GONE
            refreshFinishSetupCard()
            return
        }

        var completed = if (uiPaired()) 1 else 0
        val total = 4
        val acc = isAccessibilityServiceEnabled()
        val notif = isNotificationServiceEnabled()
        val phone = hasPhonePermissionForSetup()

        if (acc) completed++
        if (notif) completed++
        if (phone) completed++

        if (completed == total) {
            layoutSetupProgress.visibility = View.GONE
        } else {
            val percent = (completed * 100) / total
            tvSetupProgressStatus.text = "$completed of $total completed · $percent%"
            val missing = when {
                !acc && !notif && !phone -> "Enable Notification Access, Background Clipboard Service, and Phone access for full sync."
                !acc && !notif -> "Enable Notification Access and Background Clipboard Service for full sync."
                !acc -> "Enable Background Clipboard Service to keep clipboard synced automatically."
                !notif -> "Enable Notification Access to mirror notifications to your Mac."
                !phone -> "Enable Phone access to show calls and signal on your Mac."
                else -> "Enable Notification Access to mirror notifications to your Mac."
            }
            tvSetupProgressDetail.text = missing
            layoutSetupProgress.visibility = View.VISIBLE
        }
        refreshFinishSetupCard()
    }

    private fun updateAccessibilityNotice() {
        if (currentTab != TAB_HOME || isAccessibilityServiceEnabled()) {
            layoutServiceNotice.visibility = View.GONE
        } else {
            layoutServiceNotice.visibility = View.VISIBLE
        }

        if (currentTab != TAB_HOME || isNotificationServiceEnabled() || !LinkPrefs.isNotificationSyncEnabled(this)) {
            layoutNotificationNotice.visibility = View.GONE
        } else {
            layoutNotificationNotice.visibility = View.VISIBLE
        }

        if (currentTab != TAB_HOME || hasPhonePermissionForSetup()) {
            layoutPhoneAccessNotice.visibility = View.GONE
        } else {
            layoutPhoneAccessNotice.visibility = View.VISIBLE
        }
        refreshFinishSetupCard()
    }

    private fun updateSettingsUI() {
        isUpdatingSettingsUi = true
        try {
            val masterEnabled = LinkPrefs.isNotifMasterEnabled(this)
            val androidEnabled = LinkPrefs.isAndroidNotifEnabled(this)
            val macEnabled = LinkPrefs.isMacNotifEnabled(this)
            val vibrateEnabled = LinkPrefs.isPhoneVibrateEnabled(this)
            val autoAcceptFiles = LinkPrefs.isAutoAcceptFilesEnabled(this)

            switchNotifMaster.isChecked = masterEnabled
            switchAndroidNotif.isChecked = androidEnabled
            switchMacNotif.isChecked = macEnabled
            switchPhoneVibrate.isChecked = vibrateEnabled
            switchAutoAcceptFiles.isChecked = autoAcceptFiles
            switchFastLink.isChecked = LinkPrefs.isFastLinkEnabled(this)
            switchSkipSilent.isChecked = LinkPrefs.isSkipSilentNotificationsEnabled(this)

            switchAndroidNotif.isEnabled = masterEnabled
            switchMacNotif.isEnabled = masterEnabled
            layoutNotifSubOptions.alpha = if (masterEnabled) 1.0f else 0.42f
            tvNotifMasterPausedNotice.visibility = if (masterEnabled) View.GONE else View.VISIBLE

            val macEffective = masterEnabled && macEnabled
            switchPhoneVibrate.isEnabled = macEffective
            rowPhoneVibrate.alpha = if (macEffective) 1.0f else 0.42f

            val customSaveUri = LinkPrefs.getCustomSaveTreeUri(this)
            val customSaveName = LinkPrefs.getCustomSaveDisplayName(this)
            if (!customSaveUri.isNullOrBlank() && !customSaveName.isNullOrBlank()) {
                tvSaveLocationPath.text = customSaveName
                btnResetSaveLocation.visibility = View.VISIBLE
            } else {
                tvSaveLocationPath.text = "Downloads (/storage/emulated/0/Download)"
                btnResetSaveLocation.visibility = View.GONE
            }

            tvHotspotName.text = if (demoMode) "Pixel 8 Hotspot"
                else LinkPrefs.getHotspotName(this) ?: "Not set up"

            if (isNotificationServiceEnabled()) {
                tvSettingNotifAccessStatus.text = "Active"
                tvSettingNotifAccessStatus.setTextColor(colorAccent)
            } else {
                tvSettingNotifAccessStatus.text = "Required"
                tvSettingNotifAccessStatus.setTextColor(colorRed)
            }

            if (isAccessibilityServiceEnabled()) {
                tvSettingAccessibilityStatus.text = "Active"
                tvSettingAccessibilityStatus.setTextColor(colorAccent)
            } else {
                tvSettingAccessibilityStatus.text = "Required"
                tvSettingAccessibilityStatus.setTextColor(colorRed)
            }

            val paired = uiPaired()
            val isLive = paired && isLiveConnected() == true
            if (!paired) {
                tvSettingsDeviceMacName.text = "No device paired"
                tvSettingsDeviceStatus.text = "No device is paired or connected"
                tvSettingsDeviceStatus.setTextColor(colorTertiaryLabel)
                btnToggleConnectMacSettings.visibility = View.GONE
                btnUnpairMacSettings.visibility = View.GONE
            } else {
                val pairedMac = uiMacName()
                tvSettingsDeviceMacName.text = pairedMac
                btnToggleConnectMacSettings.visibility = View.VISIBLE
                btnUnpairMacSettings.visibility = View.VISIBLE

                if (isLive) {
                    tvSettingsDeviceStatus.text = "Connected · Live"
                    tvSettingsDeviceStatus.setTextColor(colorAccent)
                    btnToggleConnectMacSettings.isEnabled = true
                    btnToggleConnectMacSettings.text = "Disconnect"
                    btnToggleConnectMacSettings.setTextColor(colorLabel)
                    btnToggleConnectMacSettings.setBackgroundResource(R.drawable.bg_button_outline)
                } else {
                    tvSettingsDeviceStatus.text = if (isConnectingState) "Connecting…" else "Paired device · Disconnected"
                    tvSettingsDeviceStatus.setTextColor(if (isConnectingState) colorAccent else colorTertiaryLabel)
                    btnToggleConnectMacSettings.isEnabled = !isConnectingState
                    btnToggleConnectMacSettings.text = if (isConnectingState) "Connecting…" else "Connect"
                    btnToggleConnectMacSettings.setTextColor(if (isConnectingState) colorTertiaryLabel else colorOnAccent)
                    btnToggleConnectMacSettings.setBackgroundResource(if (isConnectingState) R.drawable.bg_button_outline else R.drawable.bg_button_lime)
                }
            }
        } finally {
            isUpdatingSettingsUi = false
        }
    }

    override fun onSettingsChanged() {
        updateAccessibilityNotice()
        updateSetupProgress()
        updateSettingsUI()
        updateUI()
    }

    override fun onTransfersChanged() {
        renderTransfersList()
    }

    private fun renderTransfersList() {
        val items = LinkFileTransferManager.transfers.toList()
        val hasCompleted = items.any { it.status == TransferStatus.COMPLETED }
        btnClearCompletedTransfers.visibility = if (hasCompleted) View.VISIBLE else View.GONE
        btnClearCompletedTransfers.setTextColor(colorAccent)

        if (items.isEmpty()) {
            layoutTransfersEmptyState.visibility = View.VISIBLE
            layoutTransfersList.visibility = View.GONE
            layoutTransfersList.removeAllViews()
            return
        }

        layoutTransfersEmptyState.visibility = View.GONE
        layoutTransfersList.visibility = View.VISIBLE
        layoutTransfersList.removeAllViews()

        val density = resources.displayMetrics.density
        val fontSemibold = safeGetFont(R.font.sf_pro_text_semibold, Typeface.DEFAULT_BOLD)
        val fontMedium = safeGetFont(R.font.sf_pro_text_medium, Typeface.DEFAULT)
        val fontMono = safeGetFont(R.font.sf_mono_medium, Typeface.MONOSPACE)

        for (item in items) {
            val isCompleted = item.status == TransferStatus.COMPLETED
            val openAction = {
                if (isCompleted) {
                    LinkFileTransferManager.openTransferredFile(this@MainActivity, item)
                }
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_panel)
                val pad = (14 * density).toInt()
                setPadding(pad, pad, pad, pad)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (10 * density).toInt()
                layoutParams = lp

                // Tap or long-press reveals complete filename
                setOnLongClickListener {
                    Toast.makeText(this@MainActivity, "${item.fileName}\n${item.formattedTotalSize}", Toast.LENGTH_LONG).show()
                    true
                }
                if (isCompleted) {
                    setOnClickListener { openAction() }
                }
            }

            // Top row: File-type badge + file details + action buttons
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // File Type Icon Tile (matching macOS menu bar app)
            val iconContainer = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.bg_file_icon_tile)
                val tilePad = (7 * density).toInt()
                setPadding(tilePad, tilePad, tilePad, tilePad)
                val lp = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                lp.marginEnd = (10 * density).toInt()
                layoutParams = lp
                if (isCompleted) {
                    setOnClickListener { openAction() }
                }
            }

            val iconView = ImageView(this).apply {
                setImageResource(item.iconResId)
                imageTintList = ColorStateList.valueOf(colorSecondaryLabel)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            if (isCompleted) {
                iconView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.green))
            }
            iconContainer.addView(iconView)
            topRow.addView(iconContainer)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = (10 * density).toInt()
                layoutParams = lp
                if (isCompleted) {
                    setOnClickListener { openAction() }
                }
            }

            // Angled / diagonal arrows matching Mac menu bar app (↗ and ↙)
            val dirArrow = if (item.direction == TransferDirection.OUTGOING) "↗ " else "↙ "
            val titleTv = TextView(this).apply {
                text = dirArrow + item.fileName
                setTextColor(colorLabel)
                typeface = fontSemibold
                textSize = 13.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setOnClickListener {
                    if (isCompleted) {
                        openAction()
                    } else {
                        Toast.makeText(this@MainActivity, item.fileName, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val subtitleText = when (item.status) {
                TransferStatus.HASHING -> if (item.isFolder) "Packaging folder…" else "${item.formattedTotalSize} · Preparing…"
                TransferStatus.WAITING_CONFIRMATION -> {
                    item.statusNote ?: if (item.direction == TransferDirection.INCOMING) {
                        "${item.formattedTotalSize} · From ${item.deviceName}"
                    } else {
                        "${item.formattedTotalSize} · Connecting to ${item.deviceName}…"
                    }
                }
                TransferStatus.TRANSFERRING -> {
                    val spd = if (item.formattedSpeed.isNotEmpty()) " · ${item.formattedSpeed}" else ""
                    "${item.percentageInt}% · ${item.formattedProgressSize}$spd"
                }
                TransferStatus.PAUSED -> "Paused (${item.percentageInt}%) · ${item.formattedProgressSize}"
                TransferStatus.VERIFYING -> "Verifying SHA-256 checksum…"
                TransferStatus.COMPLETED -> {
                    val actionText = if (item.direction == TransferDirection.OUTGOING) "Sent to ${item.deviceName}" else "Received from ${item.deviceName}"
                    val timeText = if (item.completedAt > 0) " · " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(item.completedAt)) else ""
                    "${item.formattedTotalSize} · $actionText$timeText"
                }
                TransferStatus.CANCELLED -> "Cancelled"
                TransferStatus.FAILED -> item.errorMessage ?: "Connection lost during transfer."
            }

            val subTv = TextView(this).apply {
                text = subtitleText
                setTextColor(
                    when (item.status) {
                        TransferStatus.FAILED -> colorRed
                        TransferStatus.CANCELLED -> colorTertiaryLabel
                        else -> colorSecondaryLabel
                    }
                )
                typeface = fontMedium
                textSize = 13f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (2 * density).toInt()
                layoutParams = lp
                if (isCompleted) {
                    setOnClickListener { openAction() }
                }
            }

            textCol.addView(titleTv)
            textCol.addView(subTv)

            // Optional ETA line for active transfers (Guideline 1.B)
            if (item.status == TransferStatus.TRANSFERRING && item.remainingTimeText.isNotEmpty()) {
                val etaTv = TextView(this).apply {
                    text = item.remainingTimeText
                    setTextColor(colorSecondaryLabel)
                    typeface = fontMedium
                    textSize = 11f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = (2 * density).toInt()
                    layoutParams = lp
                }
                textCol.addView(etaTv)
            }

            topRow.addView(textCol)

            // Action Buttons Column
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            fun makeActionButton(label: String, textColor: Int, bgRes: Int, onClick: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    setTextColor(textColor)
                    typeface = fontMedium
                    textSize = 12f
                    setBackgroundResource(bgRes)
                    val hPad = (11 * density).toInt()
                    val vPad = (5 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginStart = (6 * density).toInt()
                    layoutParams = lp
                    setOnClickListener { onClick() }
                }
            }

            // 36dp icon button on a cardPressed circle; 48dp touch target.
            fun makeIconActionButton(contentDesc: String, iconRes: Int, tintColor: Int, onClick: () -> Unit): View {
                val touch = (48 * density).toInt()
                val circle = (36 * density).toInt()
                val iconSz = (18 * density).toInt()
                return FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(touch, touch).apply {
                        marginStart = (2 * density).toInt()
                    }
                    addView(View(this@MainActivity).apply {
                        setBackgroundResource(R.drawable.bg_action_icon_circle)
                        layoutParams = FrameLayout.LayoutParams(circle, circle, android.view.Gravity.CENTER)
                    })
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(tintColor)
                        layoutParams = FrameLayout.LayoutParams(iconSz, iconSz, android.view.Gravity.CENTER)
                    })
                    this.contentDescription = contentDesc
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
            }

            when (item.status) {
                TransferStatus.WAITING_CONFIRMATION -> {
                    if (item.direction == TransferDirection.INCOMING) {
                        actionsRow.addView(
                            makeActionButton("Accept", colorOnAccent, R.drawable.bg_button_lime) {
                                LinkFileTransferManager.acceptIncomingFile(this, item.id)
                            }
                        )
                    }
                    actionsRow.addView(
                        makeIconActionButton("Cancel transfer", R.drawable.sf_xmark, colorTertiaryLabel) {
                            LinkFileTransferManager.cancelTransfer(item.id)
                        }
                    )
                }
                TransferStatus.TRANSFERRING -> {
                    actionsRow.addView(
                        makeIconActionButton("Pause transfer", R.drawable.sf_pause, colorSecondaryLabel) {
                            LinkFileTransferManager.pauseTransfer(item.id)
                        }
                    )
                    actionsRow.addView(
                        makeIconActionButton("Cancel transfer", R.drawable.sf_xmark, colorTertiaryLabel) {
                            LinkFileTransferManager.cancelTransfer(item.id)
                        }
                    )
                }
                TransferStatus.PAUSED -> {
                    actionsRow.addView(
                        makeIconActionButton("Resume transfer", R.drawable.sf_play, colorAccent) {
                            LinkFileTransferManager.resumeTransfer(this, item.id)
                        }
                    )
                    actionsRow.addView(
                        makeIconActionButton("Cancel transfer", R.drawable.sf_xmark, colorTertiaryLabel) {
                            LinkFileTransferManager.cancelTransfer(item.id)
                        }
                    )
                }
                TransferStatus.HASHING, TransferStatus.VERIFYING -> {
                    actionsRow.addView(
                        makeIconActionButton("Cancel transfer", R.drawable.sf_xmark, colorTertiaryLabel) {
                            LinkFileTransferManager.cancelTransfer(item.id)
                        }
                    )
                }
                TransferStatus.COMPLETED -> {
                    // No extra button needed: user taps file name, icon, or card to directly open or extract
                }
                TransferStatus.FAILED -> {
                    actionsRow.addView(
                        makeIconActionButton("Retry transfer", R.drawable.sf_arrow_clockwise, colorAccent) {
                            LinkFileTransferManager.retryTransfer(this, item.id)
                        }
                    )
                    actionsRow.addView(
                        makeIconActionButton("Dismiss transfer", R.drawable.sf_xmark, colorTertiaryLabel) {
                            LinkFileTransferManager.dismissTransfer(item.id)
                        }
                    )
                }
                TransferStatus.CANCELLED -> {
                    actionsRow.addView(
                        makeIconActionButton("Dismiss transfer", R.drawable.sf_xmark, colorTertiaryLabel) {
                            LinkFileTransferManager.dismissTransfer(item.id)
                        }
                    )
                }
            }

            if (actionsRow.childCount > 0) {
                topRow.addView(actionsRow)
            }
            card.addView(topRow)

            // Progress bar for active/paused transfers
            if (item.status == TransferStatus.TRANSFERRING || item.status == TransferStatus.PAUSED || item.status == TransferStatus.VERIFYING) {
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = (item.progress * 1000f).toInt()
                    progressTintList = ColorStateList.valueOf(
                        if (item.status == TransferStatus.PAUSED) colorTertiaryLabel else colorAccent
                    )
                    progressBackgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.card_pressed))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (5 * density).toInt()
                    )
                    lp.topMargin = (10 * density).toInt()
                    layoutParams = lp
                }
                card.addView(pb)
            }

            if (item.isExecutable) {
                val warnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = (8 * density).toInt()
                    layoutParams = lp
                    val padH = (8 * density).toInt()
                    val padV = (4 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 6 * density
                        setColor(Color.argb(30, 245, 166, 35))
                    }
                }

                val warnIcon = ImageView(this).apply {
                    setImageResource(R.drawable.sf_exclamation)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.orange))
                    val lp = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt())
                    lp.marginEnd = (6 * density).toInt()
                    layoutParams = lp
                }
                warnRow.addView(warnIcon)
                val warnTv = TextView(this).apply {
                    text = "Executable file · Verify sender before opening"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.orange))
                    typeface = fontMedium
                    textSize = 11.5f
                }
                warnRow.addView(warnTv)
                card.addView(warnRow)
            }

            layoutTransfersList.addView(card)
        }
    }

    private fun hasPhonePermissionForSetup(): Boolean {
        // Demo mode always shows the row as not granted for screenshots.
        if (demoMode) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val expected = "${packageName}/${LinkNotificationListenerService::class.java.canonicalName}"
        return flat.split(':').any { it.equals(expected, ignoreCase = true) || it.contains(packageName) }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "${packageName}/${ClipboardSyncAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabledServices.split(':').any { it.equals(expectedService, ignoreCase = true) }
    }

    private fun isLocalNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork
                if (network != null) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                         caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        return true
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                if (info != null && info.isConnected &&
                    (info.type == ConnectivityManager.TYPE_WIFI || info.type == ConnectivityManager.TYPE_ETHERNET)) {
                    return true
                }
            }
        }

        // Check if Hotspot / SoftAP / Tethering or local network interface is UP
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return false
            for (iface in interfaces) {
                if (iface.isUp && !iface.isLoopback) {
                    val name = iface.name.lowercase()
                    val isLocalIface = name.startsWith("wlan") ||
                                       name.startsWith("swlan") ||
                                       name.startsWith("ap") ||
                                       name.startsWith("softap") ||
                                       name.startsWith("rndis") ||
                                       name.startsWith("usb") ||
                                       name.startsWith("p2p") ||
                                       name.startsWith("eth")
                    if (isLocalIface) {
                        for (addr in iface.inetAddresses) {
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                return true
                            }
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        return false
    }

    private fun applyDemoState() {
        val state = pendingDemoState ?: return
        pendingDemoState = null
        when (state) {
            "pairing" -> {
                LinkPrefs.clearPairing(this)
                selectTab(TAB_HOME)
            }
            "home", "files", "files_empty", "settings" -> {
                LinkFileTransferManager.transfers.clear()
                if (state == "files") {
                    val active = FileTransferItem(
                        id = "demo-active", fileName = "Quarterly Report.pdf",
                        fileSize = 48L * 1024 * 1024, direction = TransferDirection.OUTGOING,
                        deviceName = "MacBook Pro"
                    )
                    active.status = TransferStatus.TRANSFERRING
                    active.bytesTransferred = (48L * 1024 * 1024 * 2) / 5
                    active.speedBytesPerSec = 9_400_000.0
                    val done = FileTransferItem(
                        id = "demo-done", fileName = "vacation-photos.zip",
                        fileSize = 182L * 1024 * 1024, direction = TransferDirection.OUTGOING,
                        deviceName = "MacBook Pro"
                    )
                    done.status = TransferStatus.COMPLETED
                    done.bytesTransferred = done.fileSize
                    val failed = FileTransferItem(
                        id = "demo-fail", fileName = "setup-app.apk",
                        fileSize = 64L * 1024 * 1024, direction = TransferDirection.INCOMING,
                        deviceName = "MacBook Pro"
                    )
                    failed.status = TransferStatus.FAILED
                    failed.errorMessage = "Connection lost during transfer."
                    LinkFileTransferManager.transfers.addAll(listOf(active, done, failed))
                }
                when (state) {
                    "files", "files_empty" -> selectTab(TAB_FILES)
                    "settings" -> selectTab(TAB_SETTINGS)
                    else -> selectTab(TAB_HOME)
                }
                renderTransfersList()
                updateUI()
            }
        }
    }

    private fun updateUI() {
        val paired = uiPaired()
        val connected = isLiveConnected() == true
        val macName = uiMacName()
        val localNetAvailable = isLocalNetworkAvailable(this)
        val isMultipleFailures = consecutiveFailures >= 2 || userConnectAttempts >= 2

        // Update Files tab header & CTA state
        tvFilesTargetMac.text = when {
            !paired -> "Not paired"
            connected -> macName
            else -> "$macName · Disconnected"
        }
        tvFilesTargetMac.setTextColor(if (connected) colorAccent else colorTertiaryLabel)
        btnSelectFilesToSend.alpha = if (connected) 1.0f else 0.45f
        btnSelectFolderToSend.alpha = if (connected) 1.0f else 0.45f

        if (paired) {
            layoutScreenPairing.visibility = View.GONE
            layoutScreenConnected.visibility = if (currentTab == TAB_HOME) View.VISIBLE else View.GONE
            layoutTabFiles.visibility = if (currentTab == TAB_FILES) View.VISIBLE else View.GONE
            layoutTabSettings.visibility = if (currentTab == TAB_SETTINGS) View.VISIBLE else View.GONE
            tvDeviceMacName.text = macName

            updateSettingsUI()
            updateSetupProgress()

            if (connected) {
                wasConnectedBefore = true
                userManuallyDisconnected = false
                isConnectingState = false
                stopDotBlink()
                consecutiveFailures = 0
                userConnectAttempts = 0

                // Hide troubleshooting banner
                layoutHeroCautionBanner.visibility = View.GONE

                // Header status pill
                tvStatusPill.text = "Connected"
                tvStatusPill.setTextColor(colorSecondaryLabel)
                dotStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.green))

                // Hero card (Lime)
                heroCard.setBackgroundResource(R.drawable.bg_card_panel)
                tvHeroTitle.text = "Connected to $macName"
                tvHeroTitle.setTextColor(colorLabel)
                tvHeroSub.text = if (demoConnected) "Wi-Fi" else "Wi-Fi"
                tvHeroSub.setTextColor(colorSecondaryLabel)
                tvHeroSub.visibility = View.VISIBLE

                // Devices card
                tvLiveBadge.text = "Connected"
                tvLiveBadge.setTextColor(colorAccent)

                // Bottom CTAs
                btnHeroConnect.visibility = View.GONE
            } else {
                // Paired but not connected (Idle or Reconnecting)
                val showCaution = !userManuallyDisconnected && !isConnectingState && (!localNetAvailable || isMultipleFailures)
                if (showCaution) {
                    layoutHeroCautionBanner.visibility = View.VISIBLE
                    tvHeroCautionTitle.text = "Couldn't connect to your Mac"
                    tvHeroCautionText.text = if (noTransportAvailable()) {
                        "Turn on Bluetooth to connect without Wi-Fi."
                    } else {
                        "Link couldn't reach your Mac. Please verify the following:"
                    }
                } else {
                    layoutHeroCautionBanner.visibility = View.GONE
                }

                tvLiveBadge.text = if (wasConnectedBefore && !userManuallyDisconnected) "Connection lost" else "Disconnected"
                tvLiveBadge.setTextColor(colorTertiaryLabel)

                if (isConnectingState) {
                    tvStatusPill.text = "Connecting..."
                    tvStatusPill.setTextColor(colorAccent)
                    dotStatus.backgroundTintList = ColorStateList.valueOf(colorAccent)
                    startDotBlink()

                    heroCard.setBackgroundResource(R.drawable.bg_card_panel)
                    tvHeroTitle.text = "Connecting..."
                    tvHeroTitle.setTextColor(colorLabel)
                    tvHeroSub.text = "Looking nearby over Bluetooth or Wi-Fi. This only takes a moment."
                    tvHeroSub.setTextColor(colorTertiaryLabel)
                    tvHeroSub.visibility = View.VISIBLE

                    btnHeroConnect.visibility = View.VISIBLE
                    btnHeroConnect.isEnabled = false
                    btnHeroConnect.text = "Connecting…"
                    btnHeroConnect.setTextColor(colorTertiaryLabel)
                    btnHeroConnect.setBackgroundResource(R.drawable.bg_button_outline)
                } else {
                    stopDotBlink()
                    tvStatusPill.text = if (wasConnectedBefore && !userManuallyDisconnected) "Connection lost" else "Disconnected"
                    tvStatusPill.setTextColor(colorTertiaryLabel)
                    dotStatus.backgroundTintList = ColorStateList.valueOf(colorTertiaryLabel)

                    heroCard.setBackgroundResource(R.drawable.bg_card_panel)
                    tvHeroTitle.text = if (wasConnectedBefore && !userManuallyDisconnected) "Connection lost" else "Disconnected"
                    tvHeroTitle.setTextColor(colorLabel)
                    tvHeroSub.text = if (wasConnectedBefore && !userManuallyDisconnected) {
                        "Your Mac dropped offline or moved to another network."
                    } else {
                        "Your Mac is paired. Tap below to reconnect."
                    }
                    tvHeroSub.setTextColor(colorTertiaryLabel)
                    tvHeroSub.visibility = View.VISIBLE

                    btnHeroConnect.visibility = View.VISIBLE
                    btnHeroConnect.isEnabled = true
                    btnHeroConnect.text = if (wasConnectedBefore && !userManuallyDisconnected) "Try again" else "Connect to Mac"
                    btnHeroConnect.setTextColor(colorOnAccent)
                    btnHeroConnect.setBackgroundResource(R.drawable.bg_button_lime)
                }
            }
        } else {
            // Not Paired (Screen B)
            layoutScreenConnected.visibility = View.GONE
            layoutScreenPairing.visibility = if (currentTab == TAB_HOME) View.VISIBLE else View.GONE
            layoutTabFiles.visibility = if (currentTab == TAB_FILES) View.VISIBLE else View.GONE
            layoutTabSettings.visibility = if (currentTab == TAB_SETTINGS) View.VISIBLE else View.GONE

            updateSettingsUI()

            if (isConnectingState) {
                tvStatusPill.text = "Connecting..."
                tvStatusPill.setTextColor(colorAccent)
                dotStatus.backgroundTintList = ColorStateList.valueOf(colorAccent)
                startDotBlink()

                btnPair.isEnabled = false
                btnPair.text = "Connecting…"
            } else {
                stopDotBlink()
                tvStatusPill.text = "Disconnected"
                tvStatusPill.setTextColor(colorTertiaryLabel)
                dotStatus.backgroundTintList = ColorStateList.valueOf(colorTertiaryLabel)

                val clean = etPairCode.text.toString().replace(" ", "").replace("-", "")
                btnPair.text = "Connect"
                btnPair.isEnabled = clean.length == 6
                btnPair.alpha = if (clean.length == 6) 1.0f else 0.45f
            }
        }
    }

    // MARK: - SyncListener Implementation

    override fun onConnectionStatusChanged(isConnected: Boolean, host: String?) {
        runOnUiThread {
            isConnectingState = false
            if (isConnected) {
                consecutiveFailures = 0
                userConnectAttempts = 0
                wasConnectedBefore = true
                userManuallyDisconnected = false
            }
            updateUI()
        }
    }

    override fun onConnectionFailed(consecutiveFailures: Int) {
        runOnUiThread {
            this.consecutiveFailures = consecutiveFailures
            if (isLiveConnected() != true) {
                isConnectingState = false
                if (!uiPaired()) {
                    showPairingNote(connectionErrorNote(), BannerStyle.ERROR)
                }
                updateUI()
            }
        }
    }

    override fun onPairingResult(accepted: Boolean, macName: String?) {
        runOnUiThread {
            isConnectingState = false
            if (accepted) {
                consecutiveFailures = 0
                userConnectAttempts = 0
                wasConnectedBefore = true
                userManuallyDisconnected = false
                showPairingNote("Connected. You're all set.", BannerStyle.CONNECTED)
                updateUI()
            } else {
                consecutiveFailures++
                showPairingNote(connectionErrorNote(), BannerStyle.ERROR)
                updateUI()
            }
        }
    }

    override fun onRemoteUnpair() {
        runOnUiThread {
            isConnectingState = false
            userManuallyDisconnected = false
            etPairCode.setText("")
            etServerIp.setText("")
            consecutiveFailures = 0
            userConnectAttempts = 0
            wasConnectedBefore = false
            layoutNoteBanner.visibility = View.GONE
            updateUI()
            Toast.makeText(this, "Unpaired from Mac", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPayloadReceived(payload: ClipboardPayload) {
        // Background sync only, UI feed removed per specification
    }

    override fun onLog(message: String) {
        // Internal diagnostic log - map connection failures to friendly design copy
        runOnUiThread {
            if (message.contains("Connection dropped or failed") || message.contains("Socket closed") || message.contains("failed")) {
                if (!uiPaired()) {
                    isConnectingState = false
                    showPairingNote(connectionErrorNote(), BannerStyle.ERROR)
                    updateUI()
                }
            }
        }
    }

    // MARK: - HistoryListener

    override fun onHistoryChanged() {
        // Feed removed per design specification
    }

    // MARK: - Bluetooth permissions & transport hints

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            FastLink.clearFailureCooldown()
            if (uiPaired()) {
                ClipboardSyncManager.reconnect(this)
            }
        }
    }

    private fun missingBluetoothPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                needed += Manifest.permission.BLUETOOTH_SCAN
                needed += Manifest.permission.BLUETOOTH_CONNECT
            } else {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
                needed += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }
        // Fast transfers need hotspot permissions too when enabled.
        if (LinkPrefs.isFastLinkEnabled(this) && FastLink.isSupported(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
                needed += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }
        return needed.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureBluetoothPermissions() {
        val missing = missingBluetoothPermissions()
        if (missing.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** True when neither LAN nor Bluetooth can currently carry a connection. */
    private fun noTransportAvailable(): Boolean =
        !isLocalNetworkAvailable(this) && !LinkTransport.isBluetoothReady(this)

    private fun connectionErrorNote(): String =
        if (noTransportAvailable()) {
            "Turn on Bluetooth to connect without Wi-Fi."
        } else {
            "Couldn't reach your Mac. Make sure Bluetooth is on (or both are on the same Wi-Fi) and Link is open on your Mac."
        }

    private fun requestPhonePermissions() {
        val perms = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isNotEmpty()) {
            requestPermissions(needed, 4101)
        } else {
            LinkDeviceStatus.refresh(this)
            LinkCallMonitor.refresh(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4101) {
            LinkDeviceStatus.refresh(this)
            LinkCallMonitor.refresh(this)
            refreshFinishSetupCard()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (ignored: Exception) {}
    }

    // MARK: - Personal hotspot setup dialog

    private fun showHotspotDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_hotspot, null)
        val etName = view.findViewById<EditText>(R.id.etHotspotName)
        val tilPassword = view.findViewById<TextInputLayout>(R.id.tilHotspotPassword)
        val etPassword = view.findViewById<TextInputEditText>(R.id.etHotspotPassword)
        etName.setText(LinkPrefs.getHotspotName(this) ?: "")
        etPassword.setText(LinkPrefs.getHotspotPassword(this) ?: "")
        // Show/hide password toggle.
        tilPassword.setEndIconOnClickListener {
            val showing = etPassword.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
            etPassword.inputType = if (showing)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            etPassword.setSelection(etPassword.text?.length ?: 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Personal hotspot")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val pass = etPassword.text.toString()
                if (name.isNotEmpty() && pass.isNotEmpty()) {
                    LinkPrefs.setHotspot(this, name, pass)
                    updateSettingsUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
