package com.example.videodub

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.ai.client.generativeai.GenerativeModel
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var audioPlayer: ExoPlayer
    private lateinit var videoPlayer: ExoPlayer
    private var tts: TextToSpeech? = null

    private var syncJob: Job? = null
    private var progressUpdaterJob: Job? = null
    private var delaySeconds = 10L
    private var isUserSeeking = false

    private lateinit var statusText: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var timelineSeekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    private var originalVolume = 0f
    private var dubbingVolume = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            YoutubeDL.getInstance().init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        tts = TextToSpeech(this, this)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        val aiDubButton = findViewById<Button>(R.id.aiDubButton)
        val playerView = findViewById<PlayerView>(R.id.playerView)
        timelineSeekBar = findViewById(R.id.timelineSeekBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)

        val originalVolumeBar = findViewById<SeekBar>(R.id.originalVolumeSeekBar)
        val dubbingVolumeBar = findViewById<SeekBar>(R.id.dubbingVolumeSeekBar)

        audioPlayer = ExoPlayer.Builder(this).build()
        videoPlayer = ExoPlayer.Builder(this).build()

        playerView.player = videoPlayer
        playerView.useController = false

        originalVolumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                originalVolume = progress / 100f
                audioPlayer.volume = originalVolume
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dubbingVolumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                dubbingVolume = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        aiDubButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            if (apiKey.isNotEmpty() && url.isNotEmpty()) {
                startAiDubbingSession(apiKey, url)
            } else {
                Toast.makeText(this, "لطفا کلید API و لینک ویدیو را وارد کنید", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnPlayPause).setOnClickListener {
            if (audioPlayer.isPlaying) {
                audioPlayer.pause()
                videoPlayer.pause()
                statusText.text = "متوقف شده"
            } else {
                audioPlayer.play()
                videoPlayer.play()
                statusText.text = "در حال پخش"
            }
        }
    }

    private fun startAiDubbingSession(apiKey: String, inputUrl: String) {
        lifecycleScope.launch {
            statusText.text = "در حال ارتباط با هوش مصنوعی جمنای..."

            val translatedScript = withContext(Dispatchers.IO) {
                fetchGeminiTranslation(apiKey, inputUrl)
            }

            if (translatedScript.isNullOrEmpty()) {
                statusText.text = "خطا در دریافت پاسخ از جمنای!"
                return@launch
            }

            statusText.text = "در حال استخراج و آماده‌سازی ویدیو..."
            val directUrl = withContext(Dispatchers.IO) { extractStreamUrl(inputUrl) } ?: return@launch

            val mediaItem = MediaItem.fromUri(directUrl)
            audioPlayer.setMediaItem(mediaItem)
            videoPlayer.setMediaItem(mediaItem)

            audioPlayer.prepare()
            videoPlayer.prepare()
            waitUntilReady(audioPlayer, videoPlayer)

            val duration = audioPlayer.duration.coerceAtLeast(0L)
            timelineSeekBar.max = duration.toInt()
            totalTimeText.text = formatTime(duration)

            syncBothPlayers(0L)
            startProgressUpdater()

            startTextToSpeechNarration(translatedScript)
        }
    }

    private suspend fun fetchGeminiTranslation(apiKey: String, videoUrl: String): String? {
        return try {
            val generativeModel = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = apiKey
            )
            val prompt = "یک متن دوبله و ترجمه روان به زبان فارسی برای ویدیوی زیر به صورت جملات کوتاه آماده کن تا خوانده شود: $videoUrl"
            val response = generativeModel.generateContent(prompt)
            response.text
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startTextToSpeechNarration(script: String) {
        tts?.speak(script, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun syncBothPlayers(targetAudioPositionMs: Long) {
        syncJob?.cancel()
        audioPlayer.seekTo(targetAudioPositionMs)
        audioPlayer.play()

        val mediaOffsetMs = delaySeconds * 1000L

        if (targetAudioPositionMs >= mediaOffsetMs) {
            videoPlayer.seekTo(targetAudioPositionMs - mediaOffsetMs)
            videoPlayer.play()
            statusText.text = "در حال پخش با دوبله هوش مصنوعی..."
        } else {
            videoPlayer.pause()
            videoPlayer.seekTo(0L)
            syncJob = lifecycleScope.launch {
                var remainingSec = ((mediaOffsetMs - targetAudioPositionMs) / 1000L).coerceAtLeast(1L)
                while (remainingSec > 0 && isActive) {
                    statusText.text = "شروع تصویر ویدیو تا $remainingSec ثانیه دیگر..."
                    delay(1000L)
                    remainingSec--
                }
                videoPlayer.play()
                statusText.text = "دوبله همزمان در حال اجراست"
            }
        }
    }

    private fun startProgressUpdater() {
        progressUpdaterJob = lifecycleScope.launch {
            while (isActive) {
                if (audioPlayer.isPlaying && !isUserSeeking) {
                    val pos = audioPlayer.currentPosition
                    timelineSeekBar.progress = pos.toInt()
                    currentTimeText.text = formatTime(pos)
                }
                delay(500L)
            }
        }
    }

    private fun extractStreamUrl(url: String): String? {
        return try {
            if (url.endsWith(".mp4", true)) return url
            val request = YoutubeDLRequest(url).apply { addOption("-f", "b") }
            YoutubeDL.getInstance().getInfo(request).url
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun waitUntilReady(p1: ExoPlayer, p2: ExoPlayer) {
        while (p1.playbackState != Player.STATE_READY || p2.playbackState != Player.STATE_READY) {
            delay(100)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("fa")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        progressUpdaterJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        audioPlayer.release()
        videoPlayer.release()
    }
}
