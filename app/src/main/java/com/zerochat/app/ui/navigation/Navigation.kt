package com.zerochat.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zerochat.app.ui.screens.unlock.UnlockScreen
import com.zerochat.app.ui.screens.setup.SetupScreen
import com.zerochat.app.ui.screens.ConnectScreen
import com.zerochat.app.ui.screens.chat.ChatScreen
import com.zerochat.app.ui.screens.onboarding.OnboardingScreen
import com.zerochat.app.ui.screens.onboarding.OnboardingViewModel
import com.zerochat.app.ui.screens.group.GroupSetupScreen
import com.zerochat.app.ui.screens.group.GroupChatScreen

/**
 * Navigation Routes
 */
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Onboarding : Screen("onboarding")
    object Unlock : Screen("unlock")
    object Setup : Screen("setup")
    object Connect : Screen("connect")
    object Chat : Screen("chat/{peerId}") {
        fun createRoute(peerId: String) = "chat/$peerId"
    }
    object GroupSetup : Screen("group_setup")
    object GroupChat : Screen("group_chat")
}

/**
 * Main Navigation Host
 * 
 * Flow:
 * 1. Splash -> Check onboarding completion
 * 2. Onboarding -> First launch experience
 * 3. Unlock -> Enter passphrase
 * 4. Setup -> Create vault
 * 5. Connect -> Enter shared secret
 * 6. Chat -> Live encrypted messaging
 */
@Composable
fun ZeroChatNavHost() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route
    ) {
        composable(Screen.Splash.route) {
            val viewModel: OnboardingViewModel = hiltViewModel()
            LaunchedEffect(Unit) {
                // Determine start destination
                val isComplete = viewModel.isOnboardingCompleteSync()
                if (isComplete) {
                    navController.navigate(Screen.Unlock.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                } else {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            }
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Screen.Setup.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

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
                onConnected = {
                    navController.navigate(Screen.Chat.createRoute("connected"))
                },
                onNavigateToGroup = {
                    navController.navigate(Screen.GroupSetup.route)
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

        composable(Screen.GroupSetup.route) {
            GroupSetupScreen(
                onGroupActive = {
                    navController.navigate(Screen.GroupChat.route) {
                        popUpTo(Screen.GroupSetup.route) { inclusive = true }
                    }
                },
                onBack = {
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(Screen.GroupSetup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.GroupChat.route) {
            GroupChatScreen(
                onDisconnect = {
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(Screen.GroupChat.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
