package com.willingtocode.voicepathfinder

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 0 has no foreground service to keep the process alive, so the screen
        // stays on for the duration. A call is the one thing a probe cannot test from a
        // backgrounded app.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pathfinder = (application as PathfinderApp).pathfinder
        setContent {
            PathfinderTheme {
                PathfinderScreen(pathfinder)
            }
        }
    }
}
