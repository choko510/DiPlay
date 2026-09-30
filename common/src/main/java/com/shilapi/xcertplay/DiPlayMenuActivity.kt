// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.shared.AppLanguage

class DiPlayMenuActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var connectionStatus: TextView? = null
    private var connectionMethod: TextView? = null
    private var statusIndicator: View? = null

    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshConnectionStatus()
            if (!isFinishing) handler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowStyle()
        render()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnToCarPlay()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshConnectionStatus()
    }

    override fun onResume() {
        super.onResume()
        if (resources.configuration.locales[0].language != AppLanguage.get(this)) {
            recreate()
            return
        }
        handler.removeCallbacks(statusRefresh)
        handler.post(statusRefresh)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusRefresh)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWindowStyle()
        render()
    }

    private fun applyWindowStyle() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun render() {
        connectionStatus = null
        connectionMethod = null
        statusIndicator = null

        val wide = resources.configuration.screenWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(32))
            setBackgroundColor(BG)
        }
        content.addView(label(getString(R.string.app_name), 34, TEXT, bold = true))
        content.addView(space(20))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, BORDER)
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        val indicator = View(this).apply {
            background = circle(MUTED)
        }
        statusIndicator = indicator
        statusCard.addView(indicator, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            marginEnd = dp(16)
        })
        val statusLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = label("", 22, TEXT, bold = true)
        val method = label("", 15, MUTED)
        connectionStatus = status
        connectionMethod = method
        statusLabels.addView(status)
        statusLabels.addView(method, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        })
        statusCard.addView(statusLabels, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(statusCard, LinearLayout.LayoutParams(-1, -2))
        content.addView(space(24))

        val actions = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        actions.addView(
            actionButton(getString(R.string.menu_return_carplay), primary = true) {
                returnToCarPlay()
            },
            actionLayoutParams(wide, 0, 0),
        )
        actions.addView(
            actionButton(getString(R.string.menu_carplay_youtube), primary = false) {
                openCarPlayYoutube()
            },
            actionLayoutParams(wide, 1, ACTION_SPACING_DP),
        )
        actions.addView(
            actionButton(getString(R.string.menu_vehicle_home), primary = false) {
                openVehicleHome()
            },
            actionLayoutParams(wide, 2, ACTION_SPACING_DP),
        )
        actions.addView(
            actionButton(getString(R.string.menu_settings), primary = false) {
                Log.i(TAG, "DiPlay Menu action=settings")
                startActivity(
                    Intent(this, DiPlayActivity::class.java)
                        .putExtra("page", "settings")
                        .putExtra(DiPlayActivity.EXTRA_RETURN_TO_MENU, true),
                )
            },
            actionLayoutParams(wide, 3, ACTION_SPACING_DP),
        )
        content.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(BG)
            addView(content)
        }
        setContentView(scroll)
        refreshConnectionStatus()
    }

    private fun refreshConnectionStatus() {
        val connected = CarPlayBackgroundSession.active
        val connecting = !connected && CarPlayBackgroundSession.hasSession()
        val statusResource = when {
            connected -> R.string.menu_status_connected
            connecting -> R.string.menu_status_connecting
            else -> R.string.menu_status_disconnected
        }
        val indicatorColor = when {
            connected -> CONNECTED
            connecting -> CONNECTING
            else -> MUTED
        }
        connectionStatus?.text = getString(statusResource)
        connectionMethod?.text = getString(
            R.string.menu_connection_method,
            getString(
                if (AirPlayPersistence.loadWirelessEnabled(this)) {
                    R.string.menu_transport_wireless
                } else {
                    R.string.menu_transport_usb
                },
            ),
        )
        statusIndicator?.background = circle(indicatorColor)
    }

    private fun returnToCarPlay() {
        Log.i(TAG, "DiPlay Menu action=return_carplay")
        try {
            startActivity(
                Intent(this, CarPlayHostActivity::class.java)
                    .setAction(CarPlayHostActions.EXIT_YOUTUBE_SPLIT)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        } catch (error: RuntimeException) {
            Log.w(TAG, "CarPlay host could not be opened", error)
            Toast.makeText(this, R.string.menu_return_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCarPlayYoutube() {
        Log.i(TAG, "DiPlay Menu action=carplay_youtube")
        try {
            startActivity(
                Intent(this, CarPlayHostActivity::class.java)
                    .setAction(CarPlayHostActions.ENTER_YOUTUBE_SPLIT)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        } catch (error: RuntimeException) {
            Log.w(TAG, "CarPlay + YouTube host could not be opened", error)
            Toast.makeText(this, R.string.menu_youtube_split_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openVehicleHome() {
        Log.i(TAG, "DiPlay Menu action=vehicle_home")
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Vehicle home screen could not be opened", error)
            Toast.makeText(this, R.string.menu_vehicle_home_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun actionButton(title: String, primary: Boolean, click: () -> Unit) =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 19f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(if (primary) BG else TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = RippleDrawable(
                ColorStateList.valueOf(0x336F9FD9),
                rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER),
                null,
            )
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minHeight = dp(MIN_TOUCH_TARGET_DP)
            stateListAnimator = null
            setOnClickListener { click() }
        }

    private fun actionLayoutParams(wide: Boolean, index: Int, spacingDp: Int) =
        if (wide) {
            LinearLayout.LayoutParams(0, dp(ACTION_HEIGHT_DP), 1f).apply {
                if (index > 0) marginStart = dp(spacingDp)
            }
        } else {
            LinearLayout.LayoutParams(-1, dp(ACTION_HEIGHT_DP)).apply {
                if (index > 0) topMargin = dp(spacingDp)
            }
        }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            typeface = if (bold) {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            } else {
                Typeface.create("sans-serif", Typeface.NORMAL)
            }
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun space(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DiPlayMenu"
        private const val STATUS_REFRESH_INTERVAL_MS = 1_000L
        private const val WIDE_LAYOUT_MIN_WIDTH_DP = 720
        private const val ACTION_HEIGHT_DP = 84
        private const val ACTION_SPACING_DP = 12
        private const val MIN_TOUCH_TARGET_DP = 72
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val CONNECTED = Color.rgb(71, 201, 146)
        private val CONNECTING = Color.rgb(255, 196, 128)
    }
}
