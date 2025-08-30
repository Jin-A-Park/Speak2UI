package com.example.speak2ui.ui.overlay

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.example.speak2ui.R
import com.example.speak2ui.control.Accessibility
import com.example.speak2ui.control.TooltipService
import com.example.speak2ui.speech.Starter
import com.example.speak2ui.ui.AppExitActivity


/**
 * A foreground service that displays a draggable overlay button on the screen.
 *
 * This service is responsible for creating and managing a floating button that can be moved around.
 * When the button is clicked, it checks for necessary permissions (Overlay, Microphone, Accessibility)
 * and then displays a full-screen menu with options to "Start" or "Exit" the main application functionality.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayButton: ImageButton

    // 전체화면 “시작/종료” 메뉴 뷰
    private var menuView: View? = null

    /**
     * This service does not support binding, so this method returns null.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called by the system when the service is first created.
     *
     * Initializes the [WindowManager] and creates the draggable overlay button.
     * The button's touch listener handles both dragging and click events.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        Log.d("OverlayService", "✅ OverlayService started")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutFlag = LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = LayoutParams(
            200,
            200,
            layoutFlag,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        overlayButton = ImageButton(this).apply {
            setImageResource(R.drawable.mic_off)
            setBackgroundResource(R.drawable.mic_bg)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        overlayButton.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = params.x
            private var initialY = params.y
            private var downX = 0f
            private var downY = 0f
            private val CLICK_THRESHOLD = 20

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        downX = event.rawX
                        downY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(overlayButton, params)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val dxUp = (event.rawX - downX).toInt()
                        val dyUp = (event.rawY - downY).toInt()
                        val dist2 = dxUp * dxUp + dyUp * dyUp
                        // 클릭 처리부
                        if (dist2 < CLICK_THRESHOLD * CLICK_THRESHOLD) {
                            Log.d("OverlayService", "Overlay button clicked")

                            if (!hasOverlayPermission()) {
                                Toast.makeText(
                                    this@OverlayService,
                                    "오버레이 권한이 필요합니다",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return true
                            }
                            if (!hasMicPermission()) {
                                Toast.makeText(
                                    this@OverlayService,
                                    "마이크 권한이 필요합니다",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return true
                            }
                            if (!isAnyA11yEnabled()) {
                                Toast.makeText(
                                    this@OverlayService,
                                    "접근성 서비스를 활성화해주세요",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return true
                            }
                            showStartStopMenu()
                            overlayButton.setImageResource(R.drawable.mic_on)
                        }
                        return true
                    }
                }
                return false
            }
        })
        windowManager.addView(overlayButton, params)
        Log.d("OverlayService", "✅ overlayButton added")
    }

    /**
     * Called by the system to notify a Service that it is no longer used and is being removed.
     *
     * Removes the overlay button and the start/stop menu from the screen.
     */
    override fun onDestroy() {
        super.onDestroy()
        hideStartStopMenu()
        if (::overlayButton.isInitialized) {
            try {
                windowManager.removeView(overlayButton)
            } catch (_: Exception) {
            }
            Log.d("OverlayService", "overlayButton removed")
        }
    }
    // ===== 메뉴 뷰 =====
    /**
     * Displays a full-screen menu with "Start" and "Exit" buttons.
     *
     * The "Start" button launches the [Starter] to begin the main functionality.
     * The "Exit" button launches the [AppExitActivity] to close the application.
     */
    private fun showStartStopMenu() {
        if (menuView != null) return

        val layoutFlag = LayoutParams.TYPE_APPLICATION_OVERLAY

        // 버튼을 눌러야 하므로 NOT_FOCUSABLE 사용하지 않음
        val menuParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            layoutFlag,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#0E0E0E".toColorInt())
            gravity = Gravity.CENTER
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
        }

        val btnWidth = (240 * resources.displayMetrics.density).toInt()
        val btnHeight = (80 * resources.displayMetrics.density).toInt()
        val lp = LinearLayout.LayoutParams(btnWidth, btnHeight).apply {
            topMargin = (32 * resources.displayMetrics.density).toInt()
        }

        val startBtn = Button(this).apply {
            text = "시작"
            textSize = 22f
            layoutParams = lp
            setTextColor(Color.WHITE)
            isAllCaps = false

            // 배경 Drawable을 직접 생성 및 적용
            background = RippleDrawable(
                ColorStateList.valueOf("#80FFFFFF".toColorInt()), // 터치 시 물결 효과 색상
                GradientDrawable().apply { // 버튼 기본 배경
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8 * resources.displayMetrics.density // 8dp 둥근 모서리
                    setColor("#333333".toColorInt()) // 배경색
                },
                null
            )

            setOnClickListener {
                // 메뉴 닫고 기존 플로우 실행
                hideStartStopMenu()
                startActivity(
                    Intent(this@OverlayService, Starter::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        val exitBtn = Button(this).apply {
            text = "종료"
            textSize = 22f
            layoutParams = lp
            setTextColor(Color.WHITE)
            isAllCaps = false

            // 동일한 배경 스타일을 직접 생성 및 적용
            background = RippleDrawable(
                ColorStateList.valueOf("#80FFFFFF".toColorInt()), // 터치 시 물결 효과 색상
                GradientDrawable().apply { // 버튼 기본 배경
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8 * resources.displayMetrics.density // 8dp 둥근 모서리
                    setColor("#333333".toColorInt()) // 배경색
                },
                null
            )

            setOnClickListener {
                // 메뉴/버튼 제거 후 서비스 종료
                hideStartStopMenu()
                startActivity(
                    Intent(this@OverlayService, AppExitActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        }

        root.addView(startBtn)
        root.addView(exitBtn)

        menuView = root
        windowManager.addView(menuView, menuParams)
    }

    /**
     * Hides the start/stop menu if it is currently visible.
     */
    private fun hideStartStopMenu() {
        menuView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            menuView = null
        }
    }

    // ===== 권한/상태 체크 =====
    /**
     * Checks if the app has permission to draw over other apps.
     * @return `true` if the permission is granted, `false` otherwise.
     */
    private fun hasOverlayPermission() =
        Settings.canDrawOverlays(this)

    /**
     * Checks if the app has permission to record audio.
     * @return `true` if the permission is granted, `false` otherwise.
     */
    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Checks if any of the required accessibility services are enabled.
     * This includes [TooltipService] and [Accessibility].
     * @return `true` if at least one of the services is enabled, `false` otherwise.
     */
    private fun isAnyA11yEnabled(): Boolean =
        isA11yEnabledFor(TooltipService::class.java) || isA11yEnabledFor(Accessibility::class.java)

    /**
     * Checks if a specific accessibility service is enabled.
     * @param cls The class of the [Accessibility] to check.
     * @return `true` if the specified service is enabled, `false` otherwise.
     */
    private fun isA11yEnabledFor(cls: Class<out AccessibilityService>): Boolean {
        val component = ComponentName(this, cls)
        val full = component.flattenToString()
        val short = component.flattenToShortString()

        val enabledStr = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val bySettings =
            enabledStr.split(':').any { it.equals(full, true) || it.equals(short, true) }
        if (bySettings) return true

        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val si = info.resolveInfo.serviceInfo
                si.packageName == component.packageName && si.name == component.className
            }
    }
}