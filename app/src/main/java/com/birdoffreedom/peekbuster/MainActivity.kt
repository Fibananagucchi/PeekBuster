package com.birdoffreedom.peekbuster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.birdoffreedom.peekbuster.ui.theme.MainScreen
import com.birdoffreedom.peekbuster.ui.theme.PeekBusterTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PeekBusterTheme {
                MainScreen()
            }
        }
    }
}