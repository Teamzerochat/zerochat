package com.zerochat.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zerochat.app.ui.screens.unlock.UnlockScreen
import com.zerochat.app.ui.screens.setup.SetupScreen
import com.zerochat.app.ui.screens.connect.ConnectScreen
import com.zerochat.app.ui.screens.chat.ChatScreen

/**
 * Navigation Routes
 */
sealed class Screen(val route: String) {
    object Unlock : Screen("unlock")
    object Setup : Screen("setup")
    object Connect : Screen("connect")
    object Chat : Screen("chat/{peerId}") {
        fun createRoute(peerId: String) = "chat/$peerId"
    }
}

/**
 * Main Navigation Host
 * 
 * Flow:
 * 1. Unlock -> Enter passphrase (or setup if first launch)
 * 2. Connect -> Enter peer Nym address + shared secret
 * 3. Chat -> Live encrypted messaging
 */
@Composable
fun ZeroChatNavHost() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = Screen.Unlock.route
    ) {
        composable(Screen.Unlock.route) {
            UnlockScreen(
                onUnlocked = {
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(Screen.Unlock.route) { inclusive = true }
                    }
                },
                onNeedSetup = {
                    navController.navigate(Screen.Setup.route)
                }
            )
        }
        
        composable(Screen.Setup.route) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Screen.Connect.route) {
            ConnectScreen(
                onConnected = { peerId ->
                    navController.navigate(Screen.Chat.createRoute(peerId))
                }
            )
        }
        
        composable(Screen.Chat.route) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
            ChatScreen(
                onDisconnect = {
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(Screen.Chat.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
