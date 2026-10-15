package com.example.pulltoreveal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Scaffold
import com.example.pulltoreveal.ui.theme.ScrollHideEventTheme
import com.example.pulltoreveal.ui.screen.BottomPullToRevealScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScrollHideEventTheme {
                Scaffold { innerPadding ->
                    BottomPullToRevealScreen(contentPadding = innerPadding)
                }
            }
        }
    }
}
