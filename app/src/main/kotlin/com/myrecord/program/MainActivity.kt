package com.myrecord.program

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.AndroidViewModel
import com.myrecord.data.LedgerRepository

class MainActivity : ComponentActivity() {
    private val model: RecordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Ink,
                onPrimary = Paper,
                secondary = RoseInk,
                background = Cream,
                onBackground = Ink,
                surface = Paper,
                onSurface = Ink,
                surfaceVariant = PaleButter,
                onSurfaceVariant = MutedInk,
                outline = Line
            )) {
                RecordApp(model.repository)
            }
        }
    }
}

internal class RecordViewModel(application: Application) : AndroidViewModel(application) {
    val repository = LedgerRepository.open(application)

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
