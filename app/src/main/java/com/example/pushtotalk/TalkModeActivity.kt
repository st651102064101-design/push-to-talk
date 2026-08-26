package com.example.pushtotalk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class TalkModeActivity : AppCompatActivity() {
    private lateinit var roomTitle: TextView
    private lateinit var onlineCount: TextView
    private lateinit var channelStatus: TextView
    private lateinit var talkButton: Button
    private lateinit var membersContainer: LinearLayout
    private var touching = false
    private var selfTalking = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ready = intent.getBooleanExtra(PttService.EXTRA_READY, false)
            val busy = intent.getBooleanExtra(PttService.EXTRA_BUSY, false)
            selfTalking = intent.getBooleanExtra(PttService.EXTRA_SELF_TALKING, false)
            val speaker = intent.getStringExtra(PttService.EXTRA_SPEAKER).orEmpty()
            val members = intent.getStringArrayListExtra(PttService.EXTRA_MEMBERS) ?: arrayListOf()
            val activeRoom = intent.getStringExtra(PttService.EXTRA_ACTIVE_ROOM).orEmpty()
            if (activeRoom.isNotBlank()) roomTitle.text = roomLabel(activeRoom)
            renderMembers(members, ready)
            renderTalkState(ready, busy, selfTalking, speaker)
            if (intent.getStringExtra(PttService.EXTRA_EVENT) == PttService.EVENT_FLOOR_BUSY) {
                talkButton.performHapticFeedback(HapticFeedbackConstants.REJECT)
                Toast.makeText(this@TalkModeActivity, "ช่องสัญญาณไม่ว่าง", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showImmersiveMode()
        setContentView(content())
        roomTitle.text = roomLabel(intent.getStringExtra(EXTRA_INITIAL_ROOM).orEmpty())
        talkButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    talkButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    serviceAction(PttService.ACTION_REQUEST_TALK)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseTalk()
            }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, statusReceiver, IntentFilter(PttService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        serviceAction(PttService.ACTION_REQUEST_STATE)
    }

    override fun onPause() {
        releaseTalk()
        super.onPause()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showImmersiveMode()
    }

    private fun content(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(NAVY)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        roomTitle = TextView(this).apply { text = "ห้อง"; setTextColor(WHITE); textSize = 26f; setTypeface(typeface, 1) }
        onlineCount = TextView(this).apply { text = "ออนไลน์ 0 คน"; setTextColor(GREEN); textSize = 14f; gravity = Gravity.END }
        header.addView(roomTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(onlineCount, LinearLayout.LayoutParams(dp(120), dp(48)))
        root.addView(header)

        channelStatus = TextView(this).apply {
            text = "กำลังตรวจสอบการเชื่อมต่อ"
            setTextColor(MUTED)
            textSize = 18f
            setTypeface(typeface, 1)
            gravity = Gravity.CENTER
            background = rounded(SURFACE, 18)
        }
        root.addView(channelStatus, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8); bottomMargin = dp(12) })

        talkButton = Button(this).apply {
            text = "กดค้างเพื่อพูด"
            isAllCaps = false
            setTextColor(WHITE)
            textSize = 32f
            setTypeface(typeface, 1)
            gravity = Gravity.CENTER
            isEnabled = false
            alpha = .55f
            background = rounded(BLUE, 28)
        }
        root.addView(talkButton, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(12) })

        val membersCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = rounded(SURFACE, 18)
        }
        membersCard.addView(TextView(this).apply { text = "สมาชิกในห้อง"; setTextColor(WHITE); textSize = 16f; setTypeface(typeface, 1) })
        membersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        membersCard.addView(ScrollView(this).apply { addView(membersContainer) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        root.addView(membersCard, LinearLayout.LayoutParams(-1, dp(170)))
        renderMembers(arrayListOf(), false)
        return root
    }

    private fun renderTalkState(ready: Boolean, busy: Boolean, talking: Boolean, speaker: String) {
        val canTalk = ready && (!busy || talking)
        talkButton.isEnabled = canTalk
        talkButton.alpha = if (canTalk) 1f else .62f
        when {
            !ready -> {
                channelStatus.text = "Status : OFF"
                channelStatus.setTextColor(RED)
                talkButton.text = "ยังไม่เชื่อมต่อ"
                talkButton.background = rounded(DISABLED, 28)
            }
            talking -> {
                channelStatus.text = "คุณกำลังพูด"
                channelStatus.setTextColor(AMBER)
                talkButton.text = "ปล่อยเพื่อหยุดพูด"
                talkButton.background = rounded(ORANGE, 28)
            }
            busy -> {
                channelStatus.text = "กำลังพูด: ${speaker.ifBlank { "ผู้ใช้อื่น" }}"
                channelStatus.setTextColor(RED)
                talkButton.text = "ช่องสัญญาณไม่ว่าง"
                talkButton.background = rounded(DARK_RED, 28)
            }
            else -> {
                channelStatus.text = "พร้อมพูด"
                channelStatus.setTextColor(GREEN)
                talkButton.text = "กดค้างเพื่อพูด"
                talkButton.background = rounded(BLUE, 28)
            }
        }
    }

    private fun renderMembers(members: ArrayList<String>, ready: Boolean) {
        onlineCount.text = "ออนไลน์ ${members.size} คน"
        membersContainer.removeAllViews()
        if (!ready || members.isEmpty()) {
            membersContainer.addView(TextView(this).apply {
                text = if (ready) "ยังไม่มีสมาชิกออนไลน์" else "รอการเชื่อมต่อ"
                setTextColor(MUTED); textSize = 14f; setPadding(0, dp(8), 0, dp(6))
            })
            return
        }
        members.forEach { member ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            row.addView(TextView(this).apply { text = "●"; setTextColor(GREEN); textSize = 13f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(24), -2))
            row.addView(TextView(this).apply { text = member; setTextColor(WHITE); textSize = 15f }, LinearLayout.LayoutParams(0, -2, 1f))
            membersContainer.addView(row)
        }
    }

    private fun releaseTalk() {
        if (!touching && !selfTalking) return
        touching = false
        serviceAction(PttService.ACTION_RELEASE_TALK)
    }

    private fun serviceAction(action: String) = startService(Intent(this, PttService::class.java).setAction(action))

    private fun roomLabel(room: String): String = when {
        room == "all" || room.equals("All", true) -> "CHANNEL : ALL"
        room.startsWith("room-") -> "CHANNEL : ห้อง ${room.removePrefix("room-")}"
        room.isNotBlank() -> "CHANNEL : $room"
        else -> "CHANNEL"
    }

    private fun showImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    companion object {
        const val EXTRA_INITIAL_ROOM = "initial_room"
        private val NAVY = Color.rgb(8, 17, 31)
        private val SURFACE = Color.rgb(23, 37, 60)
        private val WHITE = Color.rgb(241, 246, 252)
        private val MUTED = Color.rgb(159, 178, 204)
        private val BLUE = Color.rgb(37, 122, 255)
        private val GREEN = Color.rgb(89, 214, 137)
        private val RED = Color.rgb(255, 126, 126)
        private val AMBER = Color.rgb(255, 196, 92)
        private val ORANGE = Color.rgb(224, 126, 31)
        private val DARK_RED = Color.rgb(126, 46, 57)
        private val DISABLED = Color.rgb(52, 65, 84)
    }
}
