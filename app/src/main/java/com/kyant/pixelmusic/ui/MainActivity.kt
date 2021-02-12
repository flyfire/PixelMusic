package com.kyant.pixelmusic.ui

import android.content.*
import android.media.AudioManager
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.*
import com.kyant.inimate.layer.*
import com.kyant.pixelmusic.R
import com.kyant.pixelmusic.api.toplist.TopList
import com.kyant.pixelmusic.locals.*
import com.kyant.pixelmusic.media.*
import com.kyant.pixelmusic.ui.component.TopBar
import com.kyant.pixelmusic.ui.nowplaying.NowPlaying
import com.kyant.pixelmusic.ui.player.PlayerPlaylist
import com.kyant.pixelmusic.ui.screens.*
import com.kyant.pixelmusic.ui.theme.PixelMusicTheme
import kotlinx.coroutines.*

enum class Screens { HOME, EXPLORE, NEW_SONGS }

class MainActivity : AppCompatActivity() {
    private val mediaButtonReceiver = MediaButtonReceiver()

    @OptIn(ExperimentalMaterialApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        createNotificationChannel(Media.NOTIFICATION_CHANNEL_ID, getString(R.string.app_name))
        Media.init(this, connectionCallbacks)
        setContent {
            PixelMusicTheme(window) {
                val scope = rememberCoroutineScope()
                val navController = rememberNavController()
                val (myState, playerPlaylistState, nowPlayingState, playlistState, searchState) =
                    rememberSwipeableState(false)
                val topList = remember { mutableStateOf<TopList?>(null) }
                val isLight = MaterialTheme.colors.isLight
                val focusRequester = FocusRequester.Default
                // val softwareKeyboardController =
                //     remember { mutableStateOf<SoftwareKeyboardController?>(null) }
                BackHandler(
                    myState.targetValue or
                            playerPlaylistState.targetValue or
                            nowPlayingState.targetValue or
                            playlistState.targetValue or
                            searchState.targetValue
                ) {
                    scope.launch {
                        when {
                            myState.targetValue ->
                                myState.animateTo(false, spring(stiffness = 700f))
                            playerPlaylistState.targetValue ->
                                playerPlaylistState.animateTo(false, spring(stiffness = 700f))
                            nowPlayingState.targetValue ->
                                nowPlayingState.animateTo(false, spring(stiffness = 700f))
                            playlistState.targetValue ->
                                playlistState.animateTo(false, spring(stiffness = 700f))
                            searchState.targetValue ->
                                searchState.animateTo(false, spring(stiffness = 700f))
                        }
                    }
                }
                ProvidePixelPlayer {
                    Media.player = LocalPixelPlayer.current
                    ProvideJsonParser {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            BackLayer(
                                listOf(myState, playerPlaylistState, playlistState, searchState),
                                darkIcons = { progress, statusBarHeightRatio ->
                                    when {
                                        nowPlayingState.progressOf(constraints.maxHeight.toFloat()) >= 1f - statusBarHeightRatio / 2 -> isLight
                                        isLight -> progress <= 0.5f
                                        else -> false
                                    }
                                }
                            ) {
                                NavHost(navController, Screens.HOME.name) {
                                    composable(Screens.HOME.name) {
                                        Home(navController)
                                    }
                                    composable(Screens.EXPLORE.name) {
                                        Explore(playlistState, topList)
                                    }
                                    composable(Screens.NEW_SONGS.name) {
                                        NewSongs()
                                    }
                                }
                                TopBar(searchState, myState)
                            }
                            ForeLayer(searchState) {
                                val lazyListState = rememberLazyListState()
                                val nestedScrollConnection = remember {
                                    object : NestedScrollConnection {
                                        override fun onPostScroll(
                                            consumed: Offset,
                                            available: Offset,
                                            source: NestedScrollSource
                                        ): Offset {
                                            if (consumed.y == 0f && lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0) {
                                                scope.launch {
                                                    searchState.animateTo(false)
                                                    focusRequester.freeFocus()
                                                    // softwareKeyboardController.value?.hideSoftwareKeyboard()
                                                }
                                            }
                                            return super.onPostScroll(consumed, available, source)
                                        }
                                    }
                                }
                                Search(
                                    focusRequester,
                                    lazyListState,
                                    nestedScrollConnection
                                ) //, softwareKeyboardController)
                                LaunchedEffect(searchState.targetValue) {
                                    if (searchState.targetValue) {
                                        focusRequester.requestFocus()
                                        // softwareKeyboardController.value?.showSoftwareKeyboard()
                                    } else {
                                        focusRequester.freeFocus()
                                        // softwareKeyboardController.value?.hideSoftwareKeyboard()
                                    }
                                }
                            }
                            ForeLayer(playlistState) {
                                Playlist(topList)
                            }
                            ProvideNowPlaying(Media.nowPlaying) {
                                NowPlaying(nowPlayingState, playerPlaylistState)
                                ForeLayer(playerPlaylistState) {
                                    PlayerPlaylist()
                                }
                            }
                            ForeLayer(myState) {
                                My()
                            }
                        }
                    }
                }
            }
        }
    }

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Media.browser.sessionToken.also { token ->
                val mediaController = MediaControllerCompat(this@MainActivity, token)
                MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
                Media.syncWithPlaylists(this@MainActivity)
            }
        }

        override fun onConnectionSuspended() {}

        override fun onConnectionFailed() {}
    }

    public override fun onStart() {
        super.onStart()
        registerReceiver(mediaButtonReceiver, IntentFilter(Intent.ACTION_MEDIA_BUTTON))
        try {
            if (!Media.browser.isConnected) {
                Media.browser.connect()
            }
        } catch (e: IllegalStateException) {
            println(e)
        }
    }

    override fun onPause() {
        super.onPause()
        Media.syncPlaylistsToLocal(this)
    }

    public override fun onResume() {
        super.onResume()
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onDestroy() {
        super.onDestroy()
        Media.syncPlaylistsToLocal(this)
        unregisterReceiver(mediaButtonReceiver)
        ContextCompat.getSystemService(this, MediaPlaybackService::class.java)?.stopSelf()
        Media.restore()
    }
}