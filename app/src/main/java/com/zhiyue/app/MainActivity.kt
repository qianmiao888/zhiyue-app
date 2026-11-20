package com.zhiyue.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyue.app.data.CeremonyRepository
import com.zhiyue.app.model.Ceremony
import com.zhiyue.app.session.Mode
import com.zhiyue.app.session.SessionEngine
import com.zhiyue.app.ui.EndScreen
import com.zhiyue.app.ui.HomeScreen
import com.zhiyue.app.ui.SessionScreen
import com.zhiyue.app.ui.ZhiYueTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ZhiYueTheme {
                val context = LocalContext.current
                val ceremonies = remember {
                    CeremonyRepository(context.applicationContext).listCeremonies()
                }
                AppRoot(ceremonies)
            }
        }
    }
}

private enum class Screen { HOME, SESSION, END }

@Composable
private fun AppRoot(ceremonies: List<Ceremony>) {
    val context = LocalContext.current

    var screen by remember { mutableStateOf(Screen.HOME) }
    var engine by remember { mutableStateOf<SessionEngine?>(null) }
    var hint by remember { mutableStateOf("") }
    var selectedCeremony by remember { mutableStateOf(0) }

    val ceremony = ceremonies.getOrNull(selectedCeremony)

    fun launchSession(mode: Mode) {
        val c = ceremony ?: return
        val e = SessionEngine(context.applicationContext, c)
        engine = e
        hint = ""
        screen = Screen.SESSION
        e.start(mode)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchSession(Mode.LIVE)
        else hint = "未获得麦克风权限，可先用“演示模式”体验完整流程。"
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                engine?.let { e ->
                    if (!e.state.value.paused) e.togglePause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            ceremonies = ceremonies,
            selectedIndex = selectedCeremony,
            onSelect = { selectedCeremony = it },
            hint = hint,
            onStart = { mode ->
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (mode == Mode.LIVE && !granted) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    launchSession(mode)
                }
            },
        )

        Screen.SESSION -> {
            val e = engine
            if (e == null) {
                screen = Screen.HOME
                return
            }
            val state by e.state.collectAsState()
            SessionScreen(
                ceremony = ceremony!!,
                state = state,
                onPrev = { e.prevSegment() },
                onNext = { e.nextSegment() },
                onTogglePause = { e.togglePause() },
                onFinish = {
                    e.finish()
                    screen = Screen.END
                },
            )
            BackHandler {
                e.finish()
                screen = Screen.END
            }
        }

        Screen.END -> {
            val e = engine
            if (e == null) {
                screen = Screen.HOME
                return
            }
            val state by e.state.collectAsState()
            EndScreen(
                ceremony = ceremony!!,
                state = state,
                onBack = {
                    e.release()
                    engine = null
                    screen = Screen.HOME
                },
            )
            BackHandler {
                e.release()
                engine = null
                screen = Screen.HOME
            }
        }
    }
}
