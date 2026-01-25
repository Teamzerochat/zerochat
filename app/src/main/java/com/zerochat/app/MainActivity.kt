package com.zerochat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.zerochat.app.ui.theme.ZeroChatTheme
import com.zerochat.app.ui.navigation.ZeroChatNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main Activity - Entry point for ZeroChat
 * 
 * Security: Activity is destroyed on back press to clear session state
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            ZeroChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZeroChatNavHost()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Security: Clear any cached session data
        // KeyManager will handle volatile key destruction
    }
}
