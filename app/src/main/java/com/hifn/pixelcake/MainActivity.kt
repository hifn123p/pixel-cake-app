package com.hifn.pixelcake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hifn.pixelcake.ui.home.HomeScreen
import com.hifn.pixelcake.ui.theme.PixelCakeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 内容绘制到系统栏之下，配合主题中的透明状态栏
        enableEdgeToEdge()

        setContent {
            PixelCakeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }
}
