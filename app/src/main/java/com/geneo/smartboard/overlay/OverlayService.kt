package com.geneo.smartboard.overlay

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "geneo_toolbox_channel"
        private const val NOTIF_ID = 1001

        // Floor for the calculator's resize handle — small enough to save
        // screen space, big enough that the keys stay tappable.
        private const val CALC_MIN_WIDTH_DP = 220
        private const val CALC_MIN_HEIGHT_DP = 320
        private const val STOPWATCH_MIN_WIDTH_DP = 230
        private const val STOPWATCH_MIN_HEIGHT_DP = 260
        private const val TIMER_MIN_WIDTH_DP = 230
        private const val TIMER_MIN_HEIGHT_DP = 280
        private const val BROWSER_MIN_WIDTH_DP = 240
        private const val BROWSER_MIN_HEIGHT_DP = 280
        private const val PDF_MIN_WIDTH_DP = 260
        private const val PDF_MIN_HEIGHT_DP = 320
        // How much screen edge stays visible when the PDF viewer is maximized -
        // deliberately not edge-to-edge, so it still reads as a floating card.
        private const val PDF_MAXIMIZE_MARGIN_DP = 32

        // How often the bubble/menu force themselves back to the very top of
        // the overlay stack, in case some other app's own overlay window got
        // added after ours and is now covering it.
        private const val BRING_TO_FRONT_INTERVAL_MS = 10 * 60 * 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var inflater: LayoutInflater
    private val periodicHandler = Handler(Looper.getMainLooper())
    private val bringToFrontRunnable = object : Runnable {
        override fun run() {
            safeRun("periodic bring-to-front") { reassertOverlayOrder() }
            periodicHandler.postDelayed(this, BRING_TO_FRONT_INTERVAL_MS)
        }
    }
    private var touchSlop = 16

    // Bubble
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isDockedRight = true

    // Menu
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var isMenuOpen = false
    private var isAnimatingMenu = false

    // Tool windows
    private var stopwatchView: View? = null
    private var stopwatchController: StopwatchController? = null
    private var timerView: View? = null
    private var timerController: TimerController? = null
    private var calculatorView: View? = null
    private var calculatorController: CalculatorController? = null

    // Book browser (subject/chapter picker) — two-state UI, no separate window per state
    private var browserView: View? = null
    private var browserShowingChapters = false
    private var browserCurrentSubject: Subject? = null

    // PDF chapter viewer
    private var pdfView: View? = null
    private var pdfController: PdfChapterController? = null
    private var pdfSavedWidthPx: Int? = null
    private var pdfSavedHeightPx: Int? = null
    private var pdfMinimizedIconView: View? = null
    private var pdfMaximized = false
    private var pdfPreMaximizeBounds: IntArray? = null // [width, height, x, y]

    // Word meaning lookup popup
    private var meaningView: View? = null
    private var meaningController: WordMeaningController? = null

    // Pen / annotation overlay
    private var penView: View? = null
    // Remembers each tool's last-used size so reopening the pen tool restores
    // exactly what was set before, instead of resetting to defaults.
    private var penSavedPenSizeDp: Int? = null
    private var penSavedHighlighterSizeDp: Int? = null
    private var penSavedEraserSizeDp: Int? = null
    // Remembers the last size the user dragged the calculator to, so
    // reopening it after closing restores that size instead of resetting.
    private var calcSavedWidthPx: Int? = null
    private var calcSavedHeightPx: Int? = null
    private var stopwatchSavedWidthPx: Int? = null
    private var stopwatchSavedHeightPx: Int? = null
    private var timerSavedWidthPx: Int? = null
    private var timerSavedHeightPx: Int? = null
    private var browserSavedWidthPx: Int? = null
    private var browserSavedHeightPx: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        inflater = LayoutInflater.from(this)
        touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        createNotificationChannel()
        addBubble()
        isRunning = true
        periodicHandler.postDelayed(bringToFrontRunnable, BRING_TO_FRONT_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        periodicHandler.removeCallbacks(bringToFrontRunnable)
        removeMenu(animate = false)
        closeStopwatch()
        closeTimer()
        closeCalculator()
        closeBookBrowser()
        closePdfViewer()
        closeMeaningLookup()
        closePen()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
            channel.setShowBadge(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble_grid)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    // ---------------------------------------------------------------------
    // Bubble
    // ---------------------------------------------------------------------

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun addBubble() {
        if (bubbleView != null) return
        val view = inflater.inflate(R.layout.overlay_bubble, null)
        val (screenW, screenH) = screenSize()
        val bubbleSizePx = dp(56)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = screenW - bubbleSizePx - dp(8)
        params.y = (screenH * 0.35f).toInt()

        val dragHelper = DragHelper(
            windowManager = windowManager,
            targetView = view,
            params = params,
            touchSlopPx = touchSlop,
            onTap = { safeRun("bubble tap") { toggleMenu() } },
            onDragStart = {
                safeRun("bubble drag start") { if (isMenuOpen) removeMenu(animate = false) }
            },
            onDragEnd = { finalX, _ ->
                safeRun("bubble drag end") { snapToEdge(view, params, finalX) }
            }
        )
        view.setOnTouchListener(dragHelper)

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            // Some devices/launchers reject overlay windows in edge cases (revoked
            // permission mid-session, device policy, etc). Fail safe: stop the
            // service instead of leaving it running with nothing on screen.
            android.util.Log.w("OverlayService", "Failed to add bubble overlay view")
            stopSelf()
            return
        }
        bubbleView = view
        bubbleParams = params
        isDockedRight = params.x + bubbleSizePx / 2 > screenW / 2
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams, currentX: Int) {
        val (screenW, _) = screenSize()
        val bubbleSizePx = dp(56)
        val margin = dp(8)
        val targetX = if (currentX + bubbleSizePx / 2 < screenW / 2) {
            margin
        } else {
            screenW - bubbleSizePx - margin
        }
        isDockedRight = targetX != margin

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 220
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener {
            params.x = it.animatedValue as Int
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        animator.start()
    }

    // ---------------------------------------------------------------------
    // Menu (stopwatch / timer / calculator picker)
    // ---------------------------------------------------------------------

    private fun toggleMenu() {
        if (isAnimatingMenu) return
        if (isMenuOpen) {
            removeMenu(animate = true)
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        val bubble = bubbleView ?: return
        val bParams = bubbleParams ?: return
        if (menuView != null) return

        val view = inflater.inflate(R.layout.overlay_menu, null)
        view.layoutDirection = if (isDockedRight) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        view.alpha = 1f

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = bParams.x
        params.y = bParams.y

        // Prime the 5 rows as invisible/scaled-down; they'll animate in once positioned.
        val items = listOf(
            view.findViewById<View>(R.id.itemPen),
            view.findViewById<View>(R.id.itemBooks),
            view.findViewById<View>(R.id.itemCalculator),
            view.findViewById<View>(R.id.itemTimer),
            view.findViewById<View>(R.id.itemStopwatch)
        )
        items.forEach {
            it.alpha = 0f
            it.scaleX = 0.6f
            it.scaleY = 0.6f
            it.translationY = dp(16).toFloat()
        }

        view.findViewById<View>(R.id.btnPen).setOnClickListener {
            safeRun("open pen") { onToolChosen { openPen() } }
        }
        view.findViewById<View>(R.id.btnBooks).setOnClickListener {
            safeRun("open books") { onToolChosen { openBookBrowser() } }
        }
        view.findViewById<View>(R.id.btnCalculator).setOnClickListener {
            safeRun("open calculator") { onToolChosen { openCalculator() } }
        }
        view.findViewById<View>(R.id.btnTimer).setOnClickListener {
            safeRun("open timer") { onToolChosen { openTimer() } }
        }
        view.findViewById<View>(R.id.btnStopwatch).setOnClickListener {
            safeRun("open stopwatch") { onToolChosen { openStopwatch() } }
        }

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            android.util.Log.w("OverlayService", "Failed to add menu overlay view")
            isMenuOpen = false
            return
        }
        menuView = view
        menuParams = params
        isMenuOpen = true
        isAnimatingMenu = true

        // Wait for a real measurement pass, then reposition flush against the
        // bubble and play the staggered entrance animation.
        view.post {
            val (screenW, screenH) = screenSize()
            val bubbleSizePx = dp(56)
            val menuW = view.width
            val menuH = view.height

            params.x = if (isDockedRight) {
                (bParams.x + bubbleSizePx) - menuW
            } else {
                bParams.x
            }.coerceIn(0, (screenW - menuW).coerceAtLeast(0))

            val spaceAbove = bParams.y
            params.y = if (spaceAbove >= menuH + dp(12)) {
                bParams.y - menuH - dp(8)
            } else {
                bParams.y + bubbleSizePx + dp(8)
            }.coerceIn(0, (screenH - menuH).coerceAtLeast(0))

            runCatching { windowManager.updateViewLayout(view, params) }

            val overshoot = OvershootInterpolator(1.6f)
            items.forEachIndexed { index, item ->
                item.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setStartDelay(index * 55L)
                    .setDuration(220)
                    .setInterpolator(overshoot)
                    .withEndAction { if (index == items.lastIndex) isAnimatingMenu = false }
                    .start()
            }
        }
    }

    private fun onToolChosen(action: () -> Unit) {
        removeMenu(animate = true)
        action()
    }

    private fun removeMenu(animate: Boolean) {
        val view = menuView ?: run { isMenuOpen = false; return }
        isMenuOpen = false

        if (!animate) {
            runCatching { windowManager.removeView(view) }
            menuView = null
            menuParams = null
            isAnimatingMenu = false
            return
        }

        isAnimatingMenu = true
        val items = listOf(
            view.findViewById<View>(R.id.itemStopwatch),
            view.findViewById<View>(R.id.itemTimer),
            view.findViewById<View>(R.id.itemCalculator),
            view.findViewById<View>(R.id.itemBooks),
            view.findViewById<View>(R.id.itemPen)
        )
        items.forEachIndexed { index, item ->
            item.animate()
                .alpha(0f)
                .scaleX(0.6f)
                .scaleY(0.6f)
                .translationY(dp(16).toFloat())
                .setStartDelay(index * 40L)
                .setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    if (index == items.lastIndex) {
                        runCatching { windowManager.removeView(view) }
                        if (menuView === view) {
                            menuView = null
                            menuParams = null
                        }
                        isAnimatingMenu = false
                    }
                }
                .start()
        }
    }

    // ---------------------------------------------------------------------
    // Tool windows
    // ---------------------------------------------------------------------

    private fun addToolWindow(
        view: View,
        headerId: Int,
        widthPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        absolutePosition: Boolean = false
    ): WindowManager.LayoutParams? {
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        if (absolutePosition) {
            // Used by resizable windows (the calculator): a fixed top-left anchor
            // means growing width/height only extends to the right/bottom, which
            // is what people expect when dragging a bottom-right resize handle.
            val (screenW, screenH) = screenSize()
            params.gravity = Gravity.TOP or Gravity.START
            params.x = ((screenW - widthPx) / 2).coerceAtLeast(0)
            params.y = ((screenH - heightPx) / 2).coerceAtLeast(0)
        } else {
            params.gravity = Gravity.CENTER
            params.x = 0
            params.y = 0
        }

        view.alpha = 0f
        view.scaleX = 0.9f
        view.scaleY = 0.9f

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            android.util.Log.w("OverlayService", "Failed to add tool overlay window")
            return null
        }

        val header = view.findViewById<View>(headerId)
        val dragHelper = DragHelper(
            windowManager = windowManager,
            targetView = view,
            params = params,
            touchSlopPx = touchSlop,
            onTap = { /* tapping the header does nothing; drag to move, X to close */ }
        )
        header.setOnTouchListener(dragHelper)

        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        return params
    }

    private fun openStopwatch() {
        if (stopwatchView != null) return
        val view = inflater.inflate(R.layout.overlay_stopwatch, null)

        val (screenW, screenH) = screenSize()
        val minW = dp(STOPWATCH_MIN_WIDTH_DP)
        val minH = dp(STOPWATCH_MIN_HEIGHT_DP)
        val maxW = (screenW * 0.9f).toInt()
        val maxH = (screenH * 0.9f).toInt()
        val initW = (stopwatchSavedWidthPx ?: dp(270)).coerceIn(minW, maxW)
        val initH = (stopwatchSavedHeightPx ?: dp(380)).coerceIn(minH, maxH)

        val params = addToolWindow(
            view,
            R.id.stopwatchHeader,
            widthPx = initW,
            heightPx = initH,
            absolutePosition = true
        )
        if (params == null) return

        stopwatchController = runCatching { StopwatchController(view) }.getOrNull()
        view.findViewById<View>(R.id.btnCloseStopwatch).setOnClickListener {
            safeRun("close stopwatch") { closeStopwatch() }
        }
        setupResizeHandle(view, params, minW, minH, maxW, maxH) { w, h ->
            stopwatchSavedWidthPx = w
            stopwatchSavedHeightPx = h
        }
        stopwatchView = view
    }

    private fun closeStopwatch() {
        val view = stopwatchView ?: return
        stopwatchController?.stop()
        stopwatchController = null
        stopwatchView = null
        runCatching { windowManager.removeView(view) }
    }

    private fun openTimer() {
        if (timerView != null) return
        val view = inflater.inflate(R.layout.overlay_timer, null)

        val (screenW, screenH) = screenSize()
        val minW = dp(TIMER_MIN_WIDTH_DP)
        val minH = dp(TIMER_MIN_HEIGHT_DP)
        val maxW = (screenW * 0.9f).toInt()
        val maxH = (screenH * 0.9f).toInt()
        val initW = (timerSavedWidthPx ?: dp(270)).coerceIn(minW, maxW)
        val initH = (timerSavedHeightPx ?: dp(340)).coerceIn(minH, maxH)

        val params = addToolWindow(
            view,
            R.id.timerHeader,
            widthPx = initW,
            heightPx = initH,
            absolutePosition = true
        )
        if (params == null) return

        timerController = runCatching { TimerController(view) }.getOrNull()
        view.findViewById<View>(R.id.btnCloseTimer).setOnClickListener {
            safeRun("close timer") { closeTimer() }
        }
        setupResizeHandle(view, params, minW, minH, maxW, maxH) { w, h ->
            timerSavedWidthPx = w
            timerSavedHeightPx = h
        }
        timerView = view
    }

    private fun closeTimer() {
        val view = timerView ?: return
        timerController?.stop()
        timerController = null
        timerView = null
        runCatching { windowManager.removeView(view) }
    }

    private fun openCalculator() {
        if (calculatorView != null) return
        val view = inflater.inflate(R.layout.overlay_calculator, null)

        val (screenW, screenH) = screenSize()
        val minW = dp(CALC_MIN_WIDTH_DP)
        val minH = dp(CALC_MIN_HEIGHT_DP)
        val maxW = (screenW * 0.95f).toInt()
        val maxH = (screenH * 0.9f).toInt()
        val initW = (calcSavedWidthPx ?: dp(280)).coerceIn(minW, maxW)
        val initH = (calcSavedHeightPx ?: dp(420)).coerceIn(minH, maxH)

        val params = addToolWindow(
            view,
            R.id.calculatorHeader,
            widthPx = initW,
            heightPx = initH,
            absolutePosition = true
        )
        if (params == null) return

        val result = runCatching { CalculatorController(view) }
        if (result.isFailure) {
            android.util.Log.e("OverlayService", "Calculator failed to initialize", result.exceptionOrNull())
            android.widget.Toast.makeText(
                this,
                "Calculator couldn't load — please try again",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            runCatching { windowManager.removeView(view) }
            return
        }
        calculatorController = result.getOrNull()
        view.findViewById<View>(R.id.btnCloseCalculator).setOnClickListener {
            safeRun("close calculator") { closeCalculator() }
        }
        setupResizeHandle(view, params, minW, minH, maxW, maxH) { w, h ->
            calcSavedWidthPx = w
            calcSavedHeightPx = h
        }
        calculatorView = view
    }

    private fun closeCalculator() {
        val view = calculatorView ?: return
        calculatorController = null
        calculatorView = null
        runCatching { windowManager.removeView(view) }
    }

    // ---------------------------------------------------------------------
    // Book browser (NCERT subjects -> chapters)
    // ---------------------------------------------------------------------

    private fun openBookBrowser() {
        if (browserView != null) return
        val view = inflater.inflate(R.layout.overlay_book_browser, null)

        val (screenW, screenH) = screenSize()
        val minW = dp(BROWSER_MIN_WIDTH_DP)
        val minH = dp(BROWSER_MIN_HEIGHT_DP)
        val maxW = (screenW * 0.9f).toInt()
        val maxH = (screenH * 0.9f).toInt()
        val initW = (browserSavedWidthPx ?: dp(300)).coerceIn(minW, maxW)
        val initH = (browserSavedHeightPx ?: dp(420)).coerceIn(minH, maxH)

        val params = addToolWindow(
            view,
            R.id.browserHeader,
            widthPx = initW,
            heightPx = initH,
            absolutePosition = true
        )
        if (params == null) return

        view.findViewById<View>(R.id.btnCloseBrowser).setOnClickListener {
            safeRun("close books") { closeBookBrowser() }
        }
        view.findViewById<View>(R.id.btnBackBrowser).setOnClickListener {
            safeRun("books back") { showSubjectList(view) }
        }
        setupResizeHandle(view, params, minW, minH, maxW, maxH) { w, h ->
            browserSavedWidthPx = w
            browserSavedHeightPx = h
        }

        browserCurrentSubject = null
        browserShowingChapters = false
        showSubjectList(view)
        browserView = view
        // Delayed so this doesn't interrupt the entrance fade/scale animation
        // addToolWindow() just started on this same view.
        view.postDelayed({ safeRun("reassert order (browser)") { reassertOverlayOrder() } }, 220L)
    }

    private fun closeBookBrowser() {
        val view = browserView ?: return
        browserView = null
        browserCurrentSubject = null
        browserShowingChapters = false
        runCatching { windowManager.removeView(view) }
    }

    private fun showSubjectList(view: View) {
        browserShowingChapters = false
        browserCurrentSubject = null
        view.findViewById<TextView>(R.id.tvBrowserTitle).text = "NCERT Books"
        view.findViewById<View>(R.id.btnBackBrowser).visibility = View.GONE

        val container = view.findViewById<LinearLayout>(R.id.browserListContainer)
        container.removeAllViews()

        val subjects = BookLibrary.load(this).filter { it.chapters.isNotEmpty() }
        if (subjects.isEmpty()) {
            addBrowserMessage(
                container,
                "No books imported yet. Open the Geneo Toolbox app -> Manage NCERT Library to import a folder."
            )
            return
        }

        for (subject in subjects) {
            val row = inflater.inflate(R.layout.item_browser_row, container, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text = subject.name
            row.findViewById<TextView>(R.id.tvRowMeta).text = "${subject.chapters.size} ch"
            row.setOnClickListener {
                safeRun("open subject") { showChapterList(view, subject) }
            }
            container.addView(row)
        }
    }

    private fun showChapterList(view: View, subject: Subject) {
        browserShowingChapters = true
        browserCurrentSubject = subject
        view.findViewById<TextView>(R.id.tvBrowserTitle).text = subject.name
        view.findViewById<View>(R.id.btnBackBrowser).visibility = View.VISIBLE

        val container = view.findViewById<LinearLayout>(R.id.browserListContainer)
        container.removeAllViews()

        for (chapter in subject.chapters) {
            val row = inflater.inflate(R.layout.item_browser_row, container, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text = chapter.title
            row.findViewById<TextView>(R.id.tvRowMeta).text = ""
            row.setOnClickListener {
                safeRun("open chapter") {
                    val uri = chapter.pdfUri
                    if (uri != null) {
                        openPdfViewer("${subject.name} — ${chapter.title}", uri)
                    }
                }
            }
            container.addView(row)
        }
    }

    private fun addBrowserMessage(container: LinearLayout, message: String) {
        val tv = TextView(this)
        tv.text = message
        tv.setTextColor(resources.getColor(R.color.geneo_text_secondary, theme))
        tv.textSize = 13f
        container.addView(tv)
    }

    // ---------------------------------------------------------------------
    // PDF chapter viewer
    // ---------------------------------------------------------------------

    private fun openPdfViewer(title: String, uriString: String) {
        closePdfViewer() // only one chapter open at a time keeps this lightweight
        pdfMaximized = false
        pdfPreMaximizeBounds = null
        val view = inflater.inflate(R.layout.overlay_pdfviewer, null)

        val (screenW, screenH) = screenSize()
        val minW = dp(PDF_MIN_WIDTH_DP)
        val minH = dp(PDF_MIN_HEIGHT_DP)
        val maxW = (screenW * 0.95f).toInt()
        val maxH = (screenH * 0.9f).toInt()
        val initW = (pdfSavedWidthPx ?: dp(320)).coerceIn(minW, maxW)
        val initH = (pdfSavedHeightPx ?: dp(460)).coerceIn(minH, maxH)

        val params = addToolWindow(
            view,
            R.id.pdfHeader,
            widthPx = initW,
            heightPx = initH,
            absolutePosition = true
        )
        if (params == null) return

        view.findViewById<TextView>(R.id.tvPdfTitle).text = title
        view.findViewById<View>(R.id.btnClosePdf).setOnClickListener {
            safeRun("close pdf") { closePdfViewer() }
        }
        view.findViewById<View>(R.id.btnMinimizePdf).setOnClickListener {
            safeRun("minimize pdf") { minimizePdfViewer() }
        }
        view.findViewById<View>(R.id.btnMaximizePdf).setOnClickListener {
            safeRun("maximize pdf") { toggleMaximizePdf() }
        }

        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull()
        pdfController = if (uri != null) {
            runCatching {
                PdfChapterController(view, this, uri) { bitmap ->
                    safeRun("word meaning lookup") { showMeaningLookup(bitmap) }
                }
            }.getOrNull()
        } else null

        if (pdfController == null) {
            view.findViewById<View>(R.id.pdfContinuousView).visibility = View.GONE
            view.findViewById<TextView>(R.id.tvPdfMessage).apply {
                visibility = View.VISIBLE
                text = "Couldn't open this chapter."
            }
        }

        // The continuous PDF view relayouts and re-renders itself automatically
        // via onSizeChanged when the window is resized — no extra hook needed here.
        setupResizeHandle(view, params, minW, minH, maxW, maxH) { w, h ->
            pdfSavedWidthPx = w
            pdfSavedHeightPx = h
        }

        pdfView = view
        // Delayed so this doesn't interrupt the entrance fade/scale animation
        // addToolWindow() just started on this same view.
        view.postDelayed({ safeRun("reassert order (pdf)") { reassertOverlayOrder() } }, 220L)
    }

    private fun closePdfViewer() {
        val view = pdfView ?: return
        pdfController?.close()
        pdfController = null
        pdfView = null
        pdfMinimizedIconView?.let { runCatching { windowManager.removeView(it) } }
        pdfMinimizedIconView = null
        // Safe no-op via runCatching if the view was minimized (already
        // detached from the window) rather than currently showing.
        runCatching { windowManager.removeView(view) }
    }

    /**
     * Hides the PDF window (without closing the chapter — the renderer, page
     * cache, and current scroll/zoom position all stay alive in memory) and
     * shows a small icon near the bubble. Tapping that icon brings the exact
     * same window — same page, same zoom — straight back.
     */
    private fun minimizePdfViewer() {
        val view = pdfView ?: return
        runCatching { windowManager.removeView(view) }
        showPdfMinimizedIcon()
    }

    /**
     * Expands the PDF window to fill most of the screen, but deliberately
     * NOT edge-to-edge — a fixed margin is kept on every side, so the window
     * stays visually distinct as a floating card rather than looking like a
     * full-screen takeover. Tapping again restores the exact size/position
     * it had before maximizing.
     */
    private fun toggleMaximizePdf() {
        val view = pdfView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val btn = view.findViewById<ImageButton>(R.id.btnMaximizePdf)

        if (pdfMaximized) {
            val prev = pdfPreMaximizeBounds
            if (prev != null) {
                params.width = prev[0]
                params.height = prev[1]
                params.x = prev[2]
                params.y = prev[3]
            }
            pdfMaximized = false
            btn?.setBackgroundResource(R.drawable.bg_round_button)
        } else {
            pdfPreMaximizeBounds = intArrayOf(params.width, params.height, params.x, params.y)
            val (screenW, screenH) = screenSize()
            val margin = dp(PDF_MAXIMIZE_MARGIN_DP)
            params.width = (screenW - margin * 2).coerceAtLeast(dp(PDF_MIN_WIDTH_DP))
            params.height = (screenH - margin * 2).coerceAtLeast(dp(PDF_MIN_HEIGHT_DP))
            params.x = margin
            params.y = margin
            pdfMaximized = true
            btn?.setBackgroundResource(R.drawable.bg_calc_equals)
        }

        runCatching { windowManager.updateViewLayout(view, params) }
        pdfSavedWidthPx = params.width
        pdfSavedHeightPx = params.height
    }

    private fun showPdfMinimizedIcon() {
        if (pdfMinimizedIconView != null) return
        val bParams = bubbleParams
        val (screenW, screenH) = screenSize()
        val iconD = dp(48)

        val view = inflater.inflate(R.layout.overlay_pdf_minimized, null)
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // Docks just beneath wherever the bubble currently is; if that would
        // run off the bottom of the screen, docks just above it instead.
        val bubbleX = bParams?.x ?: (screenW - iconD - dp(8))
        val bubbleY = bParams?.y ?: dp(120)
        val bubbleSizePx = dp(56)
        params.x = bubbleX
        params.y = if (bubbleY + bubbleSizePx + iconD + dp(8) <= screenH) {
            bubbleY + bubbleSizePx + dp(8)
        } else {
            (bubbleY - iconD - dp(8)).coerceAtLeast(0)
        }

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) return

        view.setOnClickListener {
            safeRun("restore pdf") { restorePdfViewer() }
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(150).start()
        pdfMinimizedIconView = view
    }

    private fun restorePdfViewer() {
        val view = pdfView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) return

        pdfMinimizedIconView?.let { runCatching { windowManager.removeView(it) } }
        pdfMinimizedIconView = null
        view.postDelayed({ safeRun("reassert order (pdf restore)") { reassertOverlayOrder() } }, 60L)
    }

    // ---------------------------------------------------------------------
    // Word meaning lookup popup
    // ---------------------------------------------------------------------

    private fun showMeaningLookup(bitmap: android.graphics.Bitmap) {
        closeMeaningLookup() // only one lookup card at a time

        val view = inflater.inflate(R.layout.overlay_word_meaning, null)
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        params.x = 0
        params.y = 0

        view.alpha = 0f
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            bitmap.recycle()
            return
        }

        val header = view.findViewById<View>(R.id.meaningHeader)
        header.setOnTouchListener(
            DragHelper(
                windowManager = windowManager,
                targetView = view,
                params = params,
                touchSlopPx = touchSlop,
                onTap = {}
            )
        )
        view.findViewById<View>(R.id.btnCloseMeaning).setOnClickListener {
            safeRun("close meaning") { closeMeaningLookup() }
        }
        view.animate().alpha(1f).setDuration(150).start()

        val controller = WordMeaningController(view)
        controller.showLoading()
        meaningController = controller
        meaningView = view

        val ocrApiKey = Prefs.getOcrApiKey(this)
        val dictionaryApiKey = Prefs.getDictionaryApiKey(this)
        if (ocrApiKey == null) {
            controller.showError(
                "Word lookup needs an OCR.space key set first. Open the Geneo Toolbox app and add it under Step 5."
            )
            bitmap.recycle()
            return
        }

        WordLookupHelper.lookup(this, bitmap, ocrApiKey, dictionaryApiKey) { result, error ->
            // The popup may have been closed while the lookup was in flight.
            if (meaningView !== view) return@lookup
            safeRun("show meaning result") {
                if (result != null) controller.showResult(result) else controller.showError(error ?: "Lookup failed")
            }
        }
    }

    private fun closeMeaningLookup() {
        val view = meaningView ?: return
        meaningView = null
        meaningController = null
        runCatching { windowManager.removeView(view) }
    }

    // ---------------------------------------------------------------------
    // Pen (full-screen annotation, works over anything on screen)
    // ---------------------------------------------------------------------

    private fun openPen() {
        if (penView != null) return
        val view = inflater.inflate(R.layout.overlay_pen, null)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        view.alpha = 0f
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            android.util.Log.w("OverlayService", "Failed to add pen overlay window")
            return
        }

        val canvas = view.findViewById<PenCanvasView>(R.id.penCanvas)
        val savedPen = penSavedPenSizeDp
        val savedHi = penSavedHighlighterSizeDp
        val savedEraser = penSavedEraserSizeDp
        if (savedPen != null && savedHi != null && savedEraser != null) {
            runCatching { canvas.restoreSizes(savedPen, savedHi, savedEraser) }
        }
        runCatching { PenToolbarController(view, canvas) }

        view.findViewById<View>(R.id.btnClosePen).setOnClickListener {
            safeRun("close pen") { closePen() }
        }

        view.animate().alpha(1f).setDuration(180).start()
        penView = view
    }

    private fun closePen() {
        val view = penView ?: return
        runCatching {
            val canvas = view.findViewById<PenCanvasView>(R.id.penCanvas)
            penSavedPenSizeDp = canvas.penSizeDp()
            penSavedHighlighterSizeDp = canvas.highlighterSizeDp()
            penSavedEraserSizeDp = canvas.eraserSizeDp()
        }
        penView = null
        runCatching { windowManager.removeView(view) }
    }

    /**
     * Wires a tool window's bottom-right corner resize handle (present in
     * overlay_calculator.xml, overlay_stopwatch.xml, overlay_timer.xml) to
     * freely resize the window by dragging — width and height move
     * independently, within the given min/max bounds. [onResized] is called
     * on release so the caller can remember the new size for next time.
     */
    private fun setupResizeHandle(
        view: View,
        params: WindowManager.LayoutParams,
        minW: Int,
        minH: Int,
        maxW: Int,
        maxH: Int,
        onResized: (Int, Int) -> Unit
    ) {
        val handle = view.findViewById<View>(R.id.resizeHandle) ?: return

        var startWidth = params.width
        var startHeight = params.height
        var startTouchX = 0f
        var startTouchY = 0f

        handle.setOnTouchListener { _, event ->
            try {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startWidth = params.width
                        startHeight = params.height
                        startTouchX = event.rawX
                        startTouchY = event.rawY
                        true
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startTouchX).toInt()
                        val dy = (event.rawY - startTouchY).toInt()
                        params.width = (startWidth + dx).coerceIn(minW, maxW)
                        params.height = (startHeight + dy).coerceIn(minH, maxH)
                        runCatching { windowManager.updateViewLayout(view, params) }
                        true
                    }

                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        onResized(params.width, params.height)
                        true
                    }

                    else -> false
                }
            } catch (t: Throwable) {
                android.util.Log.w("OverlayService", "Recovered from error while resizing", t)
                true
            }
        }
    }

    // ---------------------------------------------------------------------
    // Utils
    // ---------------------------------------------------------------------

    /** Runs a UI callback (button tap, drag callback) without ever letting an
     *  exception escape and kill the whole overlay service/process. */
    private fun safeRun(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.w("OverlayService", "Recovered from error in $tag", t)
        }
    }

    /**
     * Re-adds a window to WindowManager using its existing LayoutParams — the
     * standard trick to move a window to the very top of the current overlay
     * stack, since WindowManager has no direct "bring to front" call. This
     * moves it to the top system-wide, not just above our own other windows,
     * which is exactly what's needed when some other app's overlay ends up
     * covering ours.
     */
    private fun bringToFront(view: View?) {
        if (view == null) return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching {
            windowManager.removeView(view)
            windowManager.addView(view, params)
        }
    }

    /**
     * Enforces the requested stacking order among our own overlay windows,
     * bottom to top: the PDF viewer at the back, the NCERT book selector
     * above it, and the bubble/menu popup always on top of both — so the
     * bubble stays reachable and the book selector never ends up hidden
     * behind an open chapter. Also used by the periodic bring-to-front timer,
     * since re-adding a window always places it above every other app's
     * overlay windows too, not just our own.
     */
    private fun reassertOverlayOrder() {
        bringToFront(pdfView)
        bringToFront(browserView)
        bringToFront(bubbleView)
        bringToFront(menuView)
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }
}
