package com.willingtocode.voicepathfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathfinder = (application as PathfinderApp).pathfinder
        setContent {
            PathfinderTheme {
                ProductRoot(pathfinder)
            }
        }
    }
}
