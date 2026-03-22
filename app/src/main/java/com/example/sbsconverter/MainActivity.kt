package com.example.sbsconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sbsconverter.ui.screens.BatchScreen
import com.example.sbsconverter.ui.screens.BatchViewModel
import com.example.sbsconverter.ui.screens.HomeScreen
import com.example.sbsconverter.ui.screens.HomeViewModel
import com.example.sbsconverter.ui.theme.SBSConverterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SBSConverterTheme {
                var currentScreen by rememberSaveable { mutableStateOf("home") }

                when (currentScreen) {
                    "home" -> {
                        val viewModel: HomeViewModel = viewModel()
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToBatch = { currentScreen = "batch" }
                        )
                    }
                    "batch" -> {
                        val viewModel: BatchViewModel = viewModel()
                        BatchScreen(
                            viewModel = viewModel,
                            onNavigateBack = { currentScreen = "home" }
                        )
                    }
                }
            }
        }
    }
}
