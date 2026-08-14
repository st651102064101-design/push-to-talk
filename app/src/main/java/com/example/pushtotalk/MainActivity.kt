package com.example.pushtotalk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.app.Dialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var endpoint: EditText
    private var selectedRoom = 1
    private val roomButtons = mutableListOf<Button>()
    private lateinit var name: EditText
    private lateinit var status: TextView
    private lateinit var serverInfo: TextView
    private lateinit var talk: Button
    private lateinit var output: Button
    private var isConnected = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) connect() else Toast.makeText(this, "ต้องอนุญาตไมโครโฟนก่อน", Toast.LENGTH_SHORT).show()
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ready = intent.getBooleanExtra(PttService.EXTRA_READY, false)
            isConnected = ready
            status.text = if (ready) "Status : ON" else "Status : OFF"
            status.setTextColor(if (ready) Color.rgb(89, 214, 137) else Color.rgb(255, 126, 126))
            talk.isEnabled = ready
            talk.alpha = if (ready) 1f else .55f
            output.isEnabled = ready
            output.alpha = if (ready) 1f else .55f
            output.text = "เสียง: " + (intent.getStringExtra(PttService.EXTRA_OUTPUT) ?: "อัตโนมัติ")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(content())
        restoreSettings()
        talk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> serviceAction(PttService.ACTION_REQUEST_TALK)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> serviceAction(PttService.ACTION_RELEASE_TALK)
            }; true
        }
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, statusReceiver, IntentFilter(PttService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        startService(Intent(this, PttService::class.java).setAction(PttService.ACTION_REQUEST_STATE))
    }
    override fun onStop() { unregisterReceiver(statusReceiver); super.onStop() }

    private fun content(): View {
        val navy = Color.rgb(10, 19, 35)
        val surface = Color.rgb(23, 37, 60)
        val muted = Color.rgb(159, 178, 204)
        val white = Color.rgb(241, 246, 252)
        val blue = Color.rgb(37, 122, 255)
        val padding = dp(20)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        val page = ScrollView(this).apply { setBackgroundColor(navy); isFillViewport = true }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, dp(28), padding, dp(28)) }
        page.addView(box)
        fun add(view: View, top: Int = 0, bottom: Int = 0) = box.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) })
        fun label(value: String) = TextView(this).apply { text = value; setTextColor(muted); textSize = 12f; letterSpacing = .08f }
        fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = rounded(surface, 16) }
        fun actionButton(text: String, color: Int) = Button(this).apply { this.text = text; isAllCaps = false; setTextColor(white); textSize = 15f; minHeight = 0; setPadding(dp(8), dp(13), dp(8), dp(13)); background = pressedBackground(color, darken(color)) }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(this).apply { text = "PTT  •  PUSH TO TALK"; setTextColor(white); textSize = 25f; setTypeface(typeface, 1) })
        heading.addView(TextView(this).apply { text = "สื่อสารทันที  •  ปลอดภัย  •  รวดเร็ว"; setTextColor(muted); textSize = 14f })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("⚙", surface).apply { textSize = 24f; setPadding(0, 0, 0, 0); setOnClickListener { showSettings() } }, LinearLayout.LayoutParams(dp(52), dp(52)))
        add(header, bottom = 22)

        endpoint = EditText(this).apply { hint = "ws://192.168.x.x:8181"; setText("ws://100.112.185.32:8181"); setSingleLine(); setTextColor(white); setHintTextColor(muted); textSize = 16f; setBackgroundColor(Color.TRANSPARENT); setPadding(0, dp(4), 0, 0) }

        val roomCard = card(); roomCard.addView(label("CHANNEL"))
        val roomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(2)) }
        for (number in 1..5) {
            val button = Button(this).apply {
                text = if (number == 5) "All" else "ห้อง $number"
                isAllCaps = false
                textSize = 12f
                minHeight = 0
                setPadding(dp(2), dp(11), dp(2), dp(11))
                setOnClickListener { selectRoom(number) }
            }
            roomButtons += button
            roomRow.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (number > 1) marginStart = dp(6) })
        }
        roomCard.addView(roomRow); add(roomCard, bottom = 12)
        selectRoom(1)

        name = EditText(this).apply { hint = "ชื่อที่แสดง"; setText("Android"); setSingleLine(); setTextColor(white); setHintTextColor(muted); textSize = 16f; setBackgroundColor(Color.TRANSPARENT); setPadding(0, dp(4), 0, 0) }
        output = actionButton("เสียง: อัตโนมัติ", Color.rgb(42, 61, 90)).apply { isEnabled = false; alpha = .55f; textSize = 13f; gravity = Gravity.CENTER; setOnClickListener { serviceAction(PttService.ACTION_CYCLE_OUTPUT) } }

        val connectionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val connectCard = LinearLayout(this).apply { gravity = Gravity.CENTER; background = rounded(surface, 16) }
        connectCard.addView(actionButton("เชื่อมต่อ", blue).apply { textSize = 15f; setOnClickListener { requestPermissionsAndConnect() } }, LinearLayout.LayoutParams(-1, -1))
        connectionRow.addView(connectCard, LinearLayout.LayoutParams(0, dp(82), 1f).apply { marginEnd = dp(5) })
        val statusCard = card()
        status = TextView(this).apply { text = "Status : OFF"; setTextColor(Color.rgb(255, 126, 126)); textSize = 15f; setTypeface(typeface, 1); gravity = Gravity.CENTER }
        serverInfo = TextView(this).apply { text = "Server : -"; setTextColor(muted); textSize = 11f; gravity = Gravity.CENTER; setSingleLine() }
        statusCard.addView(status, LinearLayout.LayoutParams(-1, dp(32)))
        statusCard.addView(serverInfo, LinearLayout.LayoutParams(-1, dp(26)))
        connectionRow.addView(statusCard, LinearLayout.LayoutParams(0, dp(82), 1f).apply { marginStart = dp(5) })
        add(connectionRow, bottom = 24)

        talk = actionButton("กดค้างเพื่อพูด", blue).apply { isEnabled = false; alpha = .55f; textSize = 26f; setTypeface(typeface, 1); gravity = Gravity.CENTER }
        box.addView(talk, LinearLayout.LayoutParams(-1, dp(130)))
        return page
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun darken(color: Int): Int = Color.rgb((Color.red(color) * .72f).toInt(), (Color.green(color) * .72f).toInt(), (Color.blue(color) * .72f).toInt())
    private fun pressedBackground(normal: Int, pressed: Int) = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, 16)); addState(intArrayOf(), rounded(normal, 16)) }
    private fun showSettings() {
        val surface = Color.rgb(23, 37, 60)
        val muted = Color.rgb(159, 178, 204)
        val white = Color.rgb(241, 246, 252)
        val blue = Color.rgb(37, 122, 255)
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); background = rounded(surface, 20) }
        fun label(text: String) = TextView(this).apply { this.text = text; setTextColor(muted); textSize = 12f; letterSpacing = .08f }
        (endpoint.parent as? android.view.ViewGroup)?.removeView(endpoint)
        (name.parent as? android.view.ViewGroup)?.removeView(name)
        (output.parent as? android.view.ViewGroup)?.removeView(output)
        content.addView(TextView(this).apply { text = "ตั้งค่า"; setTextColor(white); textSize = 24f; setTypeface(typeface, 1) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        content.addView(label("SERVER ADDRESS"))
        content.addView(endpoint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        content.addView(label("DISPLAY NAME"))
        content.addView(name, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        content.addView(label("อุปกรณ์เสียง"))
        content.addView(output, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8); bottomMargin = dp(20) })
        content.addView(Button(this).apply { text = "ทดสอบลำโพง"; isAllCaps = false; setTextColor(white); background = pressedBackground(Color.rgb(42, 61, 90), darken(Color.rgb(42, 61, 90))); setOnClickListener { serviceAction(PttService.ACTION_TEST_SPEAKER) } }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(12) })
        content.addView(Button(this).apply { text = "บันทึก"; isAllCaps = false; setTextColor(white); background = pressedBackground(blue, darken(blue)); setOnClickListener { saveSettings(false); dialog.dismiss() } }, LinearLayout.LayoutParams(-1, dp(46)))
        content.addView(TextView(this).apply { text = "Version ${BuildConfig.VERSION_NAME}"; setTextColor(muted); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(dp(340), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun selectRoom(room: Int) {
        selectedRoom = room.coerceIn(1, 5)
        roomButtons.forEachIndexed { index, button ->
            val selected = index + 1 == selectedRoom
            button.setTextColor(if (selected) Color.WHITE else Color.rgb(205, 217, 233))
            button.background = pressedBackground(if (selected) Color.rgb(37, 122, 255) else Color.rgb(42, 61, 90), Color.rgb(20, 74, 165))
        }
        if (::name.isInitialized && isConnected) {
            startService(Intent(this, PttService::class.java).apply {
                action = PttService.ACTION_SWITCH_ROOM
                putExtra(PttService.EXTRA_ROOM, if (selectedRoom == 5) "all" else "room-$selectedRoom")
                putExtra(PttService.EXTRA_NAME, name.text.toString().trim())
            })
        }
    }
    private fun requestPermissionsAndConnect() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) connect() else permissionRequest.launch(permissions.toTypedArray())
    }
    private fun connect() {
        saveSettings(false)
        ContextCompat.startForegroundService(this, Intent(this, PttService::class.java).apply {
            action = PttService.ACTION_CONNECT
            putExtra(PttService.EXTRA_ENDPOINT, endpoint.text.toString().trim())
            putExtra(PttService.EXTRA_ROOM, if (selectedRoom == 5) "all" else "room-$selectedRoom")
            putExtra(PttService.EXTRA_NAME, name.text.toString().trim())
        })
        status.text = "Status : OFF"
        status.setTextColor(Color.rgb(255, 126, 126))
    }
    private fun serviceAction(action: String) = startService(Intent(this, PttService::class.java).setAction(action))
    private fun saveSettings(showStatus: Boolean) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(PREF_ENDPOINT, endpoint.text.toString().trim())
            .putString(PREF_NAME, name.text.toString().trim())
            .putInt(PREF_ROOM, selectedRoom)
            .apply()
        updateServerInfo()
        if (showStatus) Toast.makeText(this, "บันทึกการตั้งค่าแล้ว", Toast.LENGTH_SHORT).show()
    }
    private fun restoreSettings() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        endpoint.setText(preferences.getString(PREF_ENDPOINT, endpoint.text.toString()))
        name.setText(preferences.getString(PREF_NAME, name.text.toString()))
        selectRoom(preferences.getInt(PREF_ROOM, 1).coerceIn(1, 5))
        updateServerInfo()
    }
    private fun updateServerInfo() {
        val value = endpoint.text.toString().trim()
        val host = runCatching { Uri.parse(value).host }.getOrNull().orEmpty().ifBlank { value }
        serverInfo.text = "Server : ${host.ifBlank { "-" }}"
    }
    companion object {
        private const val PREFERENCES = "ptt-settings"
        private const val PREF_ENDPOINT = "endpoint"
        private const val PREF_NAME = "name"
        private const val PREF_ROOM = "room"
    }
}
