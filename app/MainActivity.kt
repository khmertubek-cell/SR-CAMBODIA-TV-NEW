package com.srcambodia.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ImageView
import android.widget.EditText
import android.widget.Button
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logoUrl: String? = null
)

class MainActivity : AppCompatActivity() {

    private val playlistUrl = ""

    private lateinit var status: TextView
    private lateinit var channelList: ListView
    private lateinit var groupList: ListView
    private lateinit var playerView: PlayerView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var groupAdapter: ArrayAdapter<String>
    private lateinit var overlay: FrameLayout
    private lateinit var splashOverlay: FrameLayout
    private lateinit var numberOverlay: TextView
    private lateinit var channelSearch: EditText
    private lateinit var channelPanel: LinearLayout
    private lateinit var noSignalOverlay: FrameLayout
    private lateinit var noSignalText: TextView

    private val channels = mutableListOf<Channel>()
    private val favoriteUrls = mutableSetOf<String>()

    // Cache downloaded channel logos
    private val logoCache = mutableMapOf<String, Bitmap>()

    private var currentGroupFilter = "All channels"
    private var searchQuery = ""

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    private val visibleChannels = mutableListOf<Int>()
    private val groups = mutableListOf<String>()

    private var player: ExoPlayer? = null
    private var currentChannelIndex = -1
    private var selectedListIndex = -1
    private var selectedGroupIndex = 0
    private var listFocus = false
    private var returnedFromBackground = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var noInternetDialog: android.app.AlertDialog? = null
    private var wasInternetAvailable: Boolean? = null

    private val numberHandler = Handler(Looper.getMainLooper())
    private val numberBuffer = StringBuilder()
    private var numberRunnable: Runnable? = null

    private val prefs by lazy {
        getSharedPreferences("srcambodia_iptv_state", MODE_PRIVATE)
    }

    private val lastUrlKey = "last_channel_url"
    private val lastNameKey = "last_channel_name"
    private val favoritesKey = "favorite_channel_urls"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        favoriteUrls.addAll(
            prefs.getStringSet(favoritesKey, emptySet()) ?: emptySet()
        )

        buildScreen()

        if (prefs.getBoolean("activation_unlocked", false)) {
            showBrandSplash()
            downloadPlaylist()
        } else {
            showActivationScreen()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------
    // CHANNEL LOGO LOADING
    // ------------------------------------------------

    private fun loadChannelLogo(imageView: ImageView, logoUrl: String?) {

        if (logoUrl.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            imageView.setBackgroundColor(0xFF244A9C.toInt())
            return
        }

        imageView.tag = logoUrl

        val cached = synchronized(logoCache) {
            logoCache[logoUrl]
        }

        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(null)
        imageView.setBackgroundColor(0xFF244A9C.toInt())

        Thread {
            var connection: HttpURLConnection? = null

            try {
                connection = URL(logoUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 Android IPTV"
                )

                connection.inputStream.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)

                    if (bitmap != null) {
                        synchronized(logoCache) {
                            logoCache[logoUrl] = bitmap
                        }

                        runOnUiThread {
                            if (
                                !isFinishing &&
                                !isDestroyed &&
                                imageView.tag == logoUrl
                            ) {
                                imageView.setImageBitmap(bitmap)
                                imageView.setBackgroundColor(
                                    android.graphics.Color.TRANSPARENT
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep the channel logo area blank if image loading fails.
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    // ------------------------------------------------
    // ACTIVATION
    // ------------------------------------------------

    private fun showActivationScreen() {

        val activationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(0xFF101820.toInt())
            isFocusableInTouchMode = true
        }

        val brand = TextView(this).apply {
            text = "SRCAMBODIA IPTV"
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val heading = TextView(this).apply {
            text = "ACTIVATION REQUIRED"
            textSize = 18f
            setTextColor(0xFF42D6C8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(18))
        }

        val codeInput = EditText(this).apply {
            hint = "Enter activation code"
            textSize = 18f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFAAAAAA.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF263746.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val message = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFFF7777.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }

        val unlock = Button(this).apply {
            text = "UNLOCK"
            textSize = 16f
            isFocusable = true

            setOnClickListener {
                if (codeInput.text.toString().trim() == "88889999") {
                    prefs.edit()
                        .putBoolean("activation_unlocked", true)
                        .apply()

                    (activationPanel.parent as? android.view.ViewGroup)
                        ?.removeView(activationPanel)

                    showBrandSplash()
                    downloadPlaylist()
                } else {
                    message.text = "Invalid activation code. Please try again."
                    codeInput.text?.clear()
                    codeInput.requestFocus()
                }
            }
        }

        activationPanel.addView(brand)
        activationPanel.addView(heading)
        activationPanel.addView(
            codeInput,
            LinearLayout.LayoutParams(dp(300), -2)
        )
        activationPanel.addView(message)
        activationPanel.addView(
            unlock,
            LinearLayout.LayoutParams(dp(200), -2)
        )

        overlay.addView(
            activationPanel,
            FrameLayout.LayoutParams(-1, -1)
        )

        codeInput.requestFocus()
    }

    // ------------------------------------------------
    // MAIN TV SCREEN
    // ------------------------------------------------

    private fun buildScreen() {

        overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            isFocusableInTouchMode = true
        }

        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            isFocusable = true
        }

        overlay.addView(playerView)

        noSignalText = TextView(this).apply {
            text = "NO SIGNAL"
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.15f
        }

        noSignalOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF00A000.toInt())
            visibility = View.GONE
            alpha = 0f
            isClickable = false
            addView(noSignalText, FrameLayout.LayoutParams(-1, -1))
        }

        overlay.addView(
            noSignalOverlay,
            FrameLayout.LayoutParams(-1, -1)
        )

        // SWIPE CONTROLS
        val swipeDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {

                private val swipeDistance = dp(60).toFloat()
                private val swipeVelocity = dp(100).toFloat()

                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {

                    if (e1 == null) return false

                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y

                    if (
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                        kotlin.math.abs(dx) > swipeDistance &&
                        kotlin.math.abs(velocityX) > swipeVelocity
                    ) {
                        if (dx > 0) {
                            when {
                                groupList.visibility == View.VISIBLE -> return true
                                channelPanel.visibility == View.VISIBLE -> showGroupList()
                                else -> showChannelList()
                            }
                        } else {
                            when {
                                groupList.visibility == View.VISIBLE -> {
                                    groupList.visibility = View.GONE
                                    listFocus = false
                                    channelPanel.visibility = View.VISIBLE
                                    channelList.visibility = View.VISIBLE
                                    channelList.requestFocus()
                                    adapter.notifyDataSetChanged()
                                }

                                channelPanel.visibility == View.VISIBLE ->
                                    hideChannelList()
                            }
                        }
                        return true
                    }

                    if (
                        channelPanel.visibility == View.GONE &&
                        groupList.visibility == View.GONE &&
                        kotlin.math.abs(dy) > swipeDistance &&
                        kotlin.math.abs(velocityY) > swipeVelocity
                    ) {
                        if (dy < 0) changeChannel(1)
                        else changeChannel(-1)
                        return true
                    }

                    return false
                }
            }
        )

        playerView.setOnTouchListener { _, event ->
            swipeDetector.onTouchEvent(event)
            true
        }

        status = TextView(this).apply {
            visibility = View.GONE
        }

        numberOverlay = TextView(this).apply {
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            visibility = View.GONE
        }

        overlay.addView(
            numberOverlay,
            FrameLayout.LayoutParams(
                -2,
                dp(54),
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, dp(24), dp(28), 0)
            }
        )

        // CHANNEL ADAPTER WITH REAL M3U LOGOS
        adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        ) {

            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup
            ): View {

                val row = (convertView as? LinearLayout)
                    ?: LinearLayout(this@MainActivity).apply {

                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(5), dp(10), dp(5))
                        minimumHeight = dp(54)

                        val logo = ImageView(this@MainActivity).apply {
                            tag = "channel_logo"
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            isFocusable = false
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                        }

                        addView(
                            logo,
                            LinearLayout.LayoutParams(dp(48), dp(44))
                        )

                        val title = TextView(this@MainActivity).apply {
                            tag = "channel_title"
                            textSize = 17f
                            gravity = Gravity.CENTER_VERTICAL
                            maxLines = 1
                            ellipsize =
                                android.text.TextUtils.TruncateAt.END
                        }

                        addView(
                            title,
                            LinearLayout.LayoutParams(0, dp(44), 1f)
                        )

                        val star = TextView(this@MainActivity).apply {
                            tag = "channel_star"
                            textSize = 25f
                            gravity = Gravity.CENTER
                            isFocusable = false
                            isClickable = true
                            setPadding(dp(8), 0, dp(8), 0)
                            contentDescription = "Toggle favorite"
                        }

                        addView(
                            star,
                            LinearLayout.LayoutParams(dp(52), dp(48))
                        )
                    }

                val channelIndex = visibleChannels.getOrNull(position)
                val channel = channelIndex?.let {
                    channels.getOrNull(it)
                }

                val selected = position == selectedListIndex

                row.setBackgroundColor(
                    if (selected) 0xFFFFFFFF.toInt()
                    else 0xFF173B91.toInt()
                )

                val logoView =
                    row.findViewWithTag<ImageView>("channel_logo")

                val titleView =
                    row.findViewWithTag<TextView>("channel_title")

                val starView =
                    row.findViewWithTag<TextView>("channel_star")

                // Load the actual channel logo from tvg-logo
                loadChannelLogo(logoView, channel?.logoUrl)

                titleView.text =
                    channel?.name ?: getItem(position).orEmpty()

                titleView.setTextColor(
                    if (selected) 0xFF111111.toInt()
                    else 0xFFFFFFFF.toInt()
                )

                if (channel != null) {
                    val isFavorite = favoriteUrls.contains(channel.url)

                    starView.text = if (isFavorite) "★" else "☆"

                    starView.setTextColor(
                        if (isFavorite) 0xFFFFC107.toInt()
                        else if (selected) 0xFF555555.toInt()
                        else 0xFFFFFFFF.toInt()
                    )

                    starView.setOnClickListener {
                        toggleFavorite(channel)
                    }
                } else {
                    starView.text = "☆"
                    starView.setOnClickListener(null)
                }

                return row
            }
        }

        // GROUP ADAPTER
        groupAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        ) {

            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup
            ): View {

                val row = (convertView as? TextView)
                    ?: TextView(this@MainActivity).apply {
                        textSize = 16f
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(12), dp(8), dp(12))
                        minHeight = dp(46)
                    }

                row.text = getItem(position) ?: ""

                val selected =
                    position == selectedGroupIndex && listFocus

                row.setTextColor(
                    if (selected) 0xFF111111.toInt()
                    else 0xFFFFFFFF.toInt()
                )

                row.setBackgroundColor(
                    if (selected) 0xFFFFFFFF.toInt()
                    else 0xFF102B70.toInt()
                )

                return row
            }
        }

        // CHANNEL LIST
        channelList = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setBackgroundColor(0xFF173B91.toInt())
            divider = android.graphics.drawable.ColorDrawable(0xFF8EA9E8.toInt())
            dividerHeight = dp(1)
            selector = android.graphics.drawable.ColorDrawable(0x00000000)
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.GONE

            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        selectedListIndex = position
                        this@MainActivity.adapter.notifyDataSetChanged()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

            onItemClickListener =
                AdapterView.OnItemClickListener { _, _, position, _ ->
                    if (position in visibleChannels.indices) {
                        playChannelAt(visibleChannels[position])
                        hideChannelList()
                    }
                }
        }

        // GROUP LIST
        groupList = ListView(this).apply {
            adapter = this@MainActivity.groupAdapter
            setBackgroundColor(0xFF102B70.toInt())
            divider = android.graphics.drawable.ColorDrawable(0xFF8EA9E8.toInt())
            dividerHeight = dp(1)
            selector = android.graphics.drawable.ColorDrawable(0x00000000)
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.GONE

            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        selectedGroupIndex = position
                        this@MainActivity.groupAdapter.notifyDataSetChanged()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

            onItemClickListener =
                AdapterView.OnItemClickListener { _, _, position, _ ->
                    if (position in groups.indices) {
                        applyGroup(position)
                        groupList.visibility = View.GONE
                        channelPanel.visibility = View.VISIBLE
                        channelList.visibility = View.VISIBLE
                        listFocus = false
                        channelList.requestFocus()
                    }
                }
        }

        overlay.addView(
            groupList,
            FrameLayout.LayoutParams(dp(270), -1, Gravity.START)
        )

        channelSearch = EditText(this).apply {
            hint = "Search channel..."
            isFocusable = true
            isFocusableInTouchMode = true
            isSingleLine = true
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFCCCCCC.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF244A9C.toInt())
        }

        channelPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF173B91.toInt())
            visibility = View.GONE

            addView(
                channelSearch,
                LinearLayout.LayoutParams(-1, dp(52))
            )

            addView(
                channelList,
                LinearLayout.LayoutParams(-1, 0, 1f)
            )
        }

        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                searchQuery = s?.toString()?.trim().orEmpty()
                refreshVisibleChannels()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        overlay.addView(
            channelPanel,
            FrameLayout.LayoutParams(dp(370), -1, Gravity.END)
        )

        setContentView(overlay)
        playerView.requestFocus()
    }

    // ------------------------------------------------
    // NO SIGNAL ANIMATIONS
    // ------------------------------------------------

    private fun showNoSignal() {
        if (!::noSignalOverlay.isInitialized) return

        noSignalOverlay.animate().cancel()
        noSignalOverlay.visibility = View.VISIBLE
        noSignalOverlay.alpha = 0f

        noSignalOverlay.animate()
            .alpha(1f)
            .setDuration(250)
            .start()
    }

    private fun hideNoSignal() {
        if (!::noSignalOverlay.isInitialized) return

        noSignalOverlay.animate().cancel()

        noSignalOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                noSignalOverlay.visibility = View.GONE
            }
            .start()
    }

    // ------------------------------------------------
    // REMOTE CONTROL
    // ------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {

        if (event.action == KeyEvent.ACTION_DOWN) {

            if (
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                channelPanel.visibility == View.VISIBLE &&
                groupList.visibility != View.VISIBLE &&
                !channelSearch.hasFocus() &&
                channelList.selectedItemPosition <= 0
            ) return true

            val digit = when (event.keyCode) {
                KeyEvent.KEYCODE_0 -> 0
                KeyEvent.KEYCODE_1 -> 1
                KeyEvent.KEYCODE_2 -> 2
                KeyEvent.KEYCODE_3 -> 3
                KeyEvent.KEYCODE_4 -> 4
                KeyEvent.KEYCODE_5 -> 5
                KeyEvent.KEYCODE_6 -> 6
                KeyEvent.KEYCODE_7 -> 7
                KeyEvent.KEYCODE_8 -> 8
                KeyEvent.KEYCODE_9 -> 9
                else -> -1
            }

            if (
                digit >= 0 &&
                prefs.getBoolean("activation_unlocked", false) &&
                channelPanel.visibility != View.VISIBLE
            ) {
                addChannelDigit(digit)
                return true
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO -> {
                    if (channelPanel.visibility == View.VISIBLE) {
                        val position = channelList.selectedItemPosition
                        val index = visibleChannels.getOrNull(position)
                        if (index != null) {
                            toggleFavorite(channels[index])
                            return true
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (channelPanel.visibility == View.GONE) {
                        showChannelList()
                        return true
                    } else if (groupList.visibility == View.GONE) {
                        showGroupList()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BACK -> {
                    if (groupList.visibility == View.VISIBLE) {
                        groupList.visibility = View.GONE
                        listFocus = false
                        channelList.requestFocus()
                        adapter.notifyDataSetChanged()
                        return true
                    } else if (channelPanel.visibility == View.VISIBLE) {
                        hideChannelList()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (groupList.visibility == View.VISIBLE && listFocus) {
                        return super.dispatchKeyEvent(event)
                    }

                    if (
                        channelPanel.visibility == View.GONE &&
                        channels.isNotEmpty()
                    ) {
                        changeChannel(1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (groupList.visibility == View.VISIBLE && listFocus) {
                        return super.dispatchKeyEvent(event)
                    }

                    if (
                        channelPanel.visibility == View.GONE &&
                        channels.isNotEmpty()
                    ) {
                        changeChannel(-1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (groupList.visibility == View.VISIBLE && listFocus) {
                        val pos = groupList.selectedItemPosition
                        if (pos in groups.indices) {
                            applyGroup(pos)
                            groupList.visibility = View.GONE
                            channelPanel.visibility = View.VISIBLE
                            channelList.visibility = View.VISIBLE
                            listFocus = false
                            channelList.requestFocus()
                            return true
                        }
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun addChannelDigit(digit: Int) {
        if (numberBuffer.length >= 4) numberBuffer.clear()

        numberBuffer.append(digit)
        numberOverlay.text = numberBuffer.toString()
        numberOverlay.visibility = View.VISIBLE

        numberRunnable?.let {
            numberHandler.removeCallbacks(it)
        }

        numberRunnable = Runnable {
            val channelNumber = numberBuffer.toString().toIntOrNull()
            numberBuffer.clear()
            numberOverlay.visibility = View.GONE

            if (channelNumber != null && channelNumber in 1..channels.size) {
                playChannelAt(channelNumber - 1)
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Channel number not found",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        numberHandler.postDelayed(numberRunnable!!, 1200)
    }

    // ------------------------------------------------
    // CHANNEL LIST MANAGEMENT
    // ------------------------------------------------

    private fun showChannelList() {
        if (channels.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                "Channel list is not ready",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        refreshVisibleChannels()
        channelPanel.visibility = View.VISIBLE
        channelList.visibility = View.VISIBLE
        groupList.visibility = View.GONE
        listFocus = false

        selectedListIndex =
            visibleChannels.indexOf(currentChannelIndex).coerceAtLeast(0)

        channelList.setSelection(selectedListIndex)
        adapter.notifyDataSetChanged()
        channelList.requestFocus()
    }

    private fun showGroupList() {
        if (groups.isEmpty()) return

        groupList.visibility = View.VISIBLE
        listFocus = true
        groupList.setSelection(
            selectedGroupIndex.coerceIn(0, groups.lastIndex)
        )
        groupAdapter.notifyDataSetChanged()
        groupList.requestFocus()
    }

    private fun hideChannelList() {
        channelPanel.visibility = View.GONE
        groupList.visibility = View.GONE
        listFocus = false
        playerView.requestFocus()
    }

    private fun changeChannel(step: Int) {
        if (channels.isEmpty()) return

        val next = if (currentChannelIndex < 0) {
            0
        } else {
            (currentChannelIndex + step + channels.size) % channels.size
        }

        playChannelAt(next)
    }

    private fun applyGroup(position: Int) {
        if (position !in groups.indices) return

        selectedGroupIndex = position
        currentGroupFilter = groups[position]
        refreshVisibleChannels()

        selectedListIndex =
            visibleChannels.indexOf(currentChannelIndex).coerceAtLeast(0)

        channelList.setSelection(selectedListIndex)
        adapter.notifyDataSetChanged()
    }

    private fun refreshVisibleChannels() {
        visibleChannels.clear()

        val query = searchQuery.trim().lowercase()

        channels.forEachIndexed { index, channel ->
            val groupMatches = when (currentGroupFilter) {
                "All channels" -> true
                "★ Favorites" -> favoriteUrls.contains(channel.url)
                else -> channel.group == currentGroupFilter
            }

            val searchMatches =
                query.isBlank() || channel.name.lowercase().contains(query)

            if (groupMatches && searchMatches) {
                visibleChannels.add(index)
            }
        }

        adapter.clear()
        adapter.addAll(visibleChannels.map { channels[it].name })

        selectedListIndex =
            visibleChannels.indexOf(currentChannelIndex).coerceAtLeast(0)

        adapter.notifyDataSetChanged()
    }

    private fun toggleFavorite(channel: Channel) {
        if (favoriteUrls.contains(channel.url)) {
            favoriteUrls.remove(channel.url)
            android.widget.Toast.makeText(
                this,
                "Removed from Favorites",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            favoriteUrls.add(channel.url)
            android.widget.Toast.makeText(
                this,
                "Added to Favorites",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        prefs.edit()
            .putStringSet(favoritesKey, favoriteUrls.toSet())
            .apply()

        refreshVisibleChannels()
    }

    private fun playChannelAt(index: Int) {
        if (index !in channels.indices) return

        currentChannelIndex = index
        hideNoSignal()
        reconnectAttempts = 0

        val channel = channels[index]

        prefs.edit()
            .putString(lastUrlKey, channel.url)
            .putString(lastNameKey, channel.name)
            .apply()

        play(channel)
    }

    private fun resumeLastChannel() {
        if (channels.isEmpty()) return

        val savedUrl = prefs.getString(lastUrlKey, null)
        val savedName = prefs.getString(lastNameKey, null)

        val index = channels.indexOfFirst {
            it.url == savedUrl
        }.takeIf { it >= 0 }
            ?: channels.indexOfFirst {
                it.name == savedName
            }.takeIf { it >= 0 }
            ?: 0

        playChannelAt(index)
    }

    // ------------------------------------------------
    // BRAND SPLASH
    // ------------------------------------------------

    private fun showBrandSplash() {
        splashOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            isClickable = true
            isFocusable = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.srcambodia_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        content.addView(
            logoView,
            LinearLayout.LayoutParams(dp(260), dp(260))
        )

        val appName = TextView(this).apply {
            text = "SRCAMBODIA IPTV"
            textSize = 25f
            setTextColor(0xFF171717.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }

        content.addView(appName)

        val brand = TextView(this).apply {
            text = "POWERED BY SRCAMBODIA"
            textSize = 12f
            setTextColor(0xFF1685D8.toInt())
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }

        content.addView(brand)

        val loading = TextView(this).apply {
            text = "Loading playlist..."
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }

        content.addView(loading)

        splashOverlay.addView(
            content,
            FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        )

        overlay.addView(
            splashOverlay,
            FrameLayout.LayoutParams(-1, -1)
        )
    }

    private fun hideBrandSplash() {
        if (::splashOverlay.isInitialized) {
            overlay.removeView(splashOverlay)
        }
        playerView.requestFocus()
    }

    // ------------------------------------------------
    // PLAYLIST DOWNLOAD
    // ------------------------------------------------

    private fun downloadPlaylist() {
        status.text = "Downloading playlist..."

        Thread {
            try {
                val text = fetchPlaylist()
                val parsed = parsePlaylist(text)

                if (parsed.isEmpty()) {
                    throw IllegalStateException(
                        "No channel URLs found in playlist"
                    )
                }

                val temp = File(filesDir, "playlist.m3u.tmp")
                val saved = File(filesDir, "playlist.m3u")

                temp.writeText(text, Charsets.UTF_8)

                if (!temp.renameTo(saved)) {
                    saved.writeText(text, Charsets.UTF_8)
                    temp.delete()
                }

                runOnUiThread {
                    channels.clear()
                    channels.addAll(parsed)
                    rebuildGroups()
                    status.text = ""
                    hideBrandSplash()
                    resumeLastChannel()
                }

            } catch (e: Exception) {
                val saved = File(filesDir, "playlist.m3u")

                if (saved.exists()) {
                    val cached = parsePlaylist(
                        saved.readText(Charsets.UTF_8)
                    )

                    runOnUiThread {
                        channels.clear()
                        channels.addAll(cached)
                        rebuildGroups()
                        status.text = ""
                        hideBrandSplash()
                        resumeLastChannel()
                    }
                } else {
                    runOnUiThread {
                        status.text = ""
                        hideBrandSplash()

                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Playlist download failed",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    private fun fetchPlaylist(): String {
        var current = URL(playlistUrl)
        var redirects = 0

        while (redirects <= 8) {
            val connection =
                current.openConnection() as HttpURLConnection

            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false

            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )

            try {
                val code = connection.responseCode

                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException(
                            "Redirect without Location header"
                        )

                    current = URL(current, location)
                    redirects++
                    continue
                }

                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }

                val output = ByteArrayOutputStream()

                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        total += count

                        if (total > 15 * 1024 * 1024) {
                            throw IllegalStateException(
                                "Playlist exceeds 15 MB"
                            )
                        }

                        output.write(buffer, 0, count)
                    }
                }

                val body = output.toString("UTF-8")
                    .removePrefix("\uFEFF")
                    .trimStart()

                if (!body.startsWith("#EXTM3U", ignoreCase = true)) {
                    throw IllegalStateException(
                        "Server did not return a valid M3U playlist"
                    )
                }

                return body

            } finally {
                connection.disconnect()
            }
        }

        throw IllegalStateException("Too many redirects")
    }

    // ------------------------------------------------
    // M3U PARSER WITH TVG-LOGO
    // ------------------------------------------------

    private fun parsePlaylist(text: String): List<Channel> {
        val result = mutableListOf<Channel>()

        var name = "Unknown channel"
        var group = "Other"
        var logoUrl: String? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()

            if (line.startsWith("#EXTINF", ignoreCase = true)) {

                name = line.substringAfterLast(
                    ",",
                    "Unknown channel"
                ).trim().ifBlank {
                    "Unknown channel"
                }

                val groupMatch = Regex(
                    """group-title\s*=\s*"([^"]*)"""",
                    RegexOption.IGNORE_CASE
                ).find(line)

                group = groupMatch?.groupValues?.getOrNull(1)
                    ?.trim().orEmpty().ifBlank {
                        "Other"
                    }

                // Read the channel's actual logo URL from M3U
                logoUrl = Regex(
                    """tvg-logo\s*=\s*"([^"]*)"""",
                    RegexOption.IGNORE_CASE
                ).find(line)?.groupValues?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

            } else if (
                line.startsWith("http://", ignoreCase = true) ||
                line.startsWith("https://", ignoreCase = true)
            ) {

                result.add(
                    Channel(
                        name = name,
                        url = line,
                        group = group,
                        logoUrl = logoUrl
                    )
                )

                name = "Unknown channel"
                group = "Other"
                logoUrl = null
            }
        }

        return result
    }

    private fun rebuildGroups() {
        groups.clear()
        groups.add("All channels")
        groups.add("★ Favorites")
        groups.addAll(channels.map { it.group }.distinct().sorted())

        groupAdapter.clear()
        groupAdapter.addAll(groups)

        currentGroupFilter = "All channels"
        searchQuery = ""

        if (::channelSearch.isInitialized) {
            channelSearch.setText("")
        }

        refreshVisibleChannels()

        selectedGroupIndex = 0
        selectedListIndex = 0

        groupAdapter.notifyDataSetChanged()
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------
    // EXOPLAYER PLAYBACK
    // ------------------------------------------------

    private fun play(channel: Channel) {
        hideNoSignal()

        reconnectRunnable?.let {
            reconnectHandler.removeCallbacks(it)
        }

        reconnectRunnable = null
        reconnectAttempts = 0

        try {
            if (player == null) {
                player = ExoPlayer.Builder(this).build()
                playerView.player = player

                player?.addListener(object : Player.Listener {

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            reconnectAttempts = 0

                            reconnectRunnable?.let {
                                reconnectHandler.removeCallbacks(it)
                            }

                            reconnectRunnable = null
                            hideNoSignal()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        showNoSignal()
                        scheduleReconnect(error.errorCodeName)
                    }
                })
            }

            playerView.animate().cancel()
            playerView.alpha = 0.65f

            playerView.animate()
                .alpha(1f)
                .setDuration(350)
                .start()

            player?.setMediaItem(MediaItem.fromUri(channel.url))
            player?.prepare()
            player?.playWhenReady = true

        } catch (e: Exception) {
            showNoSignal()
            scheduleReconnect(e.message ?: "Playback error")
        }
    }

    // ------------------------------------------------
    // AUTOMATIC RECONNECT
    // ------------------------------------------------

    private fun scheduleReconnect(errorName: String) {
        showNoSignal()

        reconnectRunnable?.let {
            reconnectHandler.removeCallbacks(it)
        }

        if (reconnectAttempts >= maxReconnectAttempts) {
            reconnectRunnable = null

            android.widget.Toast.makeText(
                this,
                "Channel unavailable - NO SIGNAL",
                android.widget.Toast.LENGTH_SHORT
            ).show()

            return
        }

        reconnectAttempts++

        val delaySeconds = when (reconnectAttempts) {
            1 -> 2L
            2 -> 4L
            3 -> 8L
            4 -> 16L
            else -> 30L
        }

        reconnectRunnable = Runnable {
            val index = currentChannelIndex

            if (
                index in channels.indices &&
                !isFinishing &&
                !isDestroyed
            ) {
                try {
                    player?.stop()
                    player?.setMediaItem(
                        MediaItem.fromUri(channels[index].url)
                    )
                    player?.prepare()
                    player?.playWhenReady = true
                } catch (_: Exception) {
                    scheduleReconnect(errorName)
                }
            }
        }

        reconnectHandler.postDelayed(
            reconnectRunnable!!,
            delaySeconds * 1000L
        )
    }

    // ------------------------------------------------
    // INTERNET MONITOR
    // ------------------------------------------------

    private fun hasValidatedInternet(): Boolean {
        val cm = getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val network = cm.activeNetwork ?: return false
        val capabilities =
            cm.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) && capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )
    }

    private fun showNoInternetAlert() {
        if (
            isFinishing ||
            isDestroyed ||
            noInternetDialog?.isShowing == true
        ) return

        noInternetDialog =
            android.app.AlertDialog.Builder(this)
                .setTitle("No Internet Connection")
                .setMessage(
                    "Your device is not connected to the Internet. Please check Wi-Fi or mobile data, then try again."
                )
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .create()

        noInternetDialog?.show()
    }

    private fun showInternetRestoredMessage() {
        android.widget.Toast.makeText(
            this,
            "Internet connection restored",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun registerInternetMonitor() {
        if (networkCallback != null) return

        connectivityManager = getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val callback =
            object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        val available = hasValidatedInternet()

                        if (
                            wasInternetAvailable == false &&
                            available
                        ) {
                            noInternetDialog?.dismiss()
                            showInternetRestoredMessage()
                        }

                        wasInternetAvailable = available

                        if (!available) {
                            showNoInternetAlert()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    runOnUiThread {
                        wasInternetAvailable = false
                        showNoInternetAlert()
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    runOnUiThread {
                        val available =
                            capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_INTERNET
                            ) &&
                            capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED
                            )

                        if (!available) {
                            wasInternetAvailable = false
                            showNoInternetAlert()
                        } else if (wasInternetAvailable == false) {
                            wasInternetAvailable = true
                            noInternetDialog?.dismiss()
                            showInternetRestoredMessage()
                        } else {
                            wasInternetAvailable = true
                        }
                    }
                }
            }

        networkCallback = callback

        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
            wasInternetAvailable = hasValidatedInternet()

            if (wasInternetAvailable == false) {
                showNoInternetAlert()
            }
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    private fun unregisterInternetMonitor() {
        val callback = networkCallback ?: return

        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }

        networkCallback = null
        noInternetDialog?.dismiss()
        noInternetDialog = null
    }

    // ------------------------------------------------
    // APP LIFECYCLE
    // ------------------------------------------------

    override fun onStart() {
        super.onStart()

        if (prefs.getBoolean("activation_unlocked", false)) {
            registerInternetMonitor()
        }
    }

    override fun onStop() {
        unregisterInternetMonitor()
        super.onStop()
    }

    override fun onPause() {
        returnedFromBackground = true
        stopPlayback()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        if (returnedFromBackground) {
            returnedFromBackground = false

            if (channels.isNotEmpty()) {
                resumeLastChannel()
            }
        }
    }

    private fun stopPlayback() {
        reconnectRunnable?.let {
            reconnectHandler.removeCallbacks(it)
        }

        reconnectRunnable = null

        numberRunnable?.let {
            numberHandler.removeCallbacks(it)
        }

        numberRunnable = null
        numberBuffer.clear()

        if (::numberOverlay.isInitialized) {
            numberOverlay.visibility = View.GONE
        }

        if (::playerView.isInitialized) {
            playerView.player = null
        }

        player?.run {
            playWhenReady = false
            stop()
            release()
        }

        player = null
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}
