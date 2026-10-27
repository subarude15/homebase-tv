package com.homebase.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.homebase.tv.ui.HomebaseRoot
import com.homebase.tv.ui.theme.HomebaseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HomebaseApp).container
        setContent { HomebaseTheme { HomebaseRoot(container) } }
    }
}
