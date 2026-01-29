package net.troutpancake.mikeradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import android.util.Log
import android.media.AudioFocusRequest
import android.media.AudioManager


class RadioMediaService : MediaBrowserServiceCompat() {



    // ✅ Start with 3. Add your full 15 once this works end-to-end.
    // IMPORTANT: Replace the URLs with your real Icecast stream URLs.
    data class Station(val id: String, val name: String, val url: String, val subtitle: String? = null)

    // Fallback (always works offline)
    private val fallbackStations = listOf(
        Station("ambient", "Ambient", "$STREAM_BASE/ambient.mp3"),
        Station("jazz", "Jazz", "$STREAM_BASE/jazz.mp3"),
        Station("rock", "Rock", "$STREAM_BASE/rock.mp3"),
    )

    // Active stations shown to Android Auto (mutates after discovery)
    private val stations = mutableListOf<Station>()

    private lateinit var mediaSession: MediaSessionCompat
    private var player: MediaPlayer? = null
    private var currentStationId: String? = null
    private var isPlaying: Boolean = false
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        mediaSession = MediaSessionCompat(this, "MikeRadioSession").apply {
            setCallback(sessionCallback)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }

        sessionToken = mediaSession.sessionToken

        bootstrapStations()
        fetchAndUpdateStationsAsync()

        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
    }


    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        // Single root; AA will call onLoadChildren("ROOT").
        return BrowserRoot("ROOT", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != "ROOT") {
            result.sendResult(mutableListOf())
            return
        }

        val items = stations.map { station ->
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(station.id)
                .setTitle(station.name)
                .setSubtitle(station.subtitle)
                .build()


            MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }.toMutableList()

        result.sendResult(items)
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null) return

            // Tap same station again -> stop (your “toggle” behavior)
            if (mediaId == currentStationId && isPlaying) {
                stopPlayback()
                return
            }

            val station = stations.firstOrNull { it.id == mediaId } ?: return
            startPlayback(station)
        }

        override fun onPlay() {
            // If AA hits play with no selection, resume last station if available.
            val id = currentStationId ?: return
            val station = stations.firstOrNull { it.id == id } ?: return
            startPlayback(station)
        }

        override fun onPause() {
            stopPlayback()
        }

        override fun onStop() {
            stopPlayback()
        }
    }

    private fun startPlayback(station: Station) {
        currentStationId = station.id

        // Update metadata so AA shows station name
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, station.name)
                .build()
        )
        if (!requestAudioFocus()) {
            // If we can't get focus, don't start.
            val focusOk = requestAudioFocus()
            Log.e("MIKERADIO_FOCUS", "Audio focus ok=$focusOk")
            if (!focusOk) return

            return
        }

        stopPlayerOnly()

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            Log.i("MikeRadio", "Preparing: name=${station.name} url=${station.url}")

            setDataSource(station.url)
            setOnPreparedListener {
                Log.i("MikeRadio", "Prepared OK, starting playback")
                start()
                this@RadioMediaService.isPlaying = true
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)

                // Foreground keeps playback alive more reliably.
                startForeground(NOTIF_ID, buildNotification(station.name))
            }
            setOnInfoListener { _, what, extra ->
                Log.i("MikeRadio", "MediaPlayer info what=$what extra=$extra")
                false
            }
            setOnErrorListener { _, what, extra ->
                Log.e("MikeRadio", "MediaPlayer ERROR what=$what extra=$extra url=${station.url}")
                stopPlayback()
                true
            }

            prepareAsync()
        }

        updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
    }

    private fun stopPlayback() {
        stopPlayerOnly()
        this@RadioMediaService.isPlaying = false
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(true)
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null

    }

    private fun stopPlayerOnly() {
        try {
            player?.stop()
        } catch (_: Exception) { }
        try {
            player?.release()
        } catch (_: Exception) { }
        player = null
    }

    private fun updatePlaybackState(state: Int) {
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildNotification(stationName: String): Notification {
        ensureNotificationChannel()

        return NotificationCompat.Builder(this, NOTIF_CHAN_ID)
            .setContentTitle("Mike Radio")
            .setContentText(stationName)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(NOTIF_CHAN_ID)
        if (existing != null) return

        nm.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHAN_ID,
                "Mike Radio Playback",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
    private fun bootstrapStations() {
        // 1) Load cache
        val cached = loadStationsFromCache()
        if (cached.isNotEmpty()) {
            stations.clear()
            stations.addAll(cached)
            return
        }

        // 2) Fallback if no cache
        stations.clear()
        stations.addAll(fallbackStations)
    }

    private fun fetchAndUpdateStationsAsync() {
        // Keep it simple: background thread via single executor
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            try {
                val jsonText = httpGet(DIRECTORY_URL)
                val discovered = parseIcecastStations(jsonText)

                if (discovered.isNotEmpty()) {
                    android.os.Handler(mainLooper).post {
                        stations.clear()
                        stations.addAll(discovered)
                        saveStationsToCache(discovered)
                        notifyChildrenChanged("ROOT")
                    }
                }


            } catch (_: Exception) {
                // Silent failure: AA still has cache/fallback
            }
        }
    }

    private fun httpGet(urlStr: String): String {
        val url = java.net.URL(urlStr)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

        conn.inputStream.use { input ->
            return input.bufferedReader().readText()
        }
    }

    /**
     * Icecast sometimes returns:
     * icestats.source = { ... }  (single object)
     * or icestats.source = [ ... ] (array)
     */
    private fun parseIcecastStations(jsonText: String): List<Station> {
        val out = mutableListOf<Station>()
        val root = org.json.JSONObject(jsonText)
        val icestats = root.optJSONObject("icestats") ?: return out
        val source = icestats.opt("source") ?: return out

        val sources: List<org.json.JSONObject> = when (source) {
            is org.json.JSONArray -> {
                (0 until source.length())
                    .mapNotNull { idx -> source.optJSONObject(idx) }
            }
            is org.json.JSONObject -> listOf(source)
            else -> emptyList()
        }

        for (s in sources) {
            val serverName = s.optString("server_name", "").trim()
            if (serverName.isEmpty()) continue

            val serverDesc = s.optString("server_description", "").trim().ifEmpty { null }

            // listenurl might be http://0.0.0.0:8000/ambient.mp3, but we only want the path:
            val listenUrl = s.optString("listenurl", "").trim()
            val mountPath = extractPath(listenUrl) ?: continue  // "/ambient.mp3"

            // Build the URL your phone can reach (through Caddy/Tailscale):
            val streamUrl = STREAM_BASE.trimEnd('/') + mountPath

            // Stable id: use mount without leading "/" and without extension
            val id = mountPath.trimStart('/').substringBefore('.').ifEmpty { slugify(serverName) }

            out.add(Station(id = id, name = serverName, url = streamUrl, subtitle = serverDesc))
        }

        // stable ordering
        return out.sortedBy { it.name.lowercase() }
    }

    private fun extractPath(url: String): String? {
        return try {
            val u = java.net.URI(url)
            val p = u.path
            if (p.isNullOrBlank()) null else p
        } catch (_: Exception) {
            // If listenurl is weird, try a dumb fallback:
            val idx = url.indexOf('/', startIndex = url.indexOf("://").let { if (it == -1) 0 else it + 3 })
            if (idx == -1) null else url.substring(idx)
        }
    }

    private fun slugify(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "station" }
    }
    private fun saveStationsToCache(list: List<Station>) {
        val arr = org.json.JSONArray()
        for (st in list) {
            val obj = org.json.JSONObject()
                .put("id", st.id)
                .put("name", st.name)
                .put("url", st.url)
                .put("subtitle", st.subtitle ?: "")
            arr.put(obj)
        }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY_STATIONS_JSON, arr.toString())
            .apply()
    }

    private fun loadStationsFromCache(): List<Station> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREFS_KEY_STATIONS_JSON, null) ?: return emptyList()

        return try {
            val arr = org.json.JSONArray(raw)
            val out = mutableListOf<Station>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                val name = obj.optString("name", "")
                val url = obj.optString("url", "")
                val subtitle = obj.optString("subtitle", "").ifEmpty { null }
                if (id.isNotBlank() && name.isNotBlank() && url.isNotBlank()) {
                    out.add(Station(id, name, url, subtitle))
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun requestAudioFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        return if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    // Keep it simple: if we lose focus, stop.
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stopPlayback()
                    }
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stopPlayback()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }


    companion object {
        private const val DIRECTORY_URL = "https://jellyfin.trout-pancake.ts.net/radio/status-json.xsl"
        private const val STREAM_BASE = "https://jellyfin.trout-pancake.ts.net/radio"
        private const val PREFS_NAME = "mike_radio_prefs"
        private const val PREFS_KEY_STATIONS_JSON = "stations_json"

        private const val NOTIF_CHAN_ID = "mike_radio_playback"
        private const val NOTIF_ID = 1001
    }
}
