package com.meetsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meetsnap.app.ui.AppViewModel
import com.meetsnap.app.ui.MeetSnapRoot
import com.meetsnap.app.ui.MeetSnapTheme
import com.google.firebase.FirebaseApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val configured = FirebaseApp.getApps(this).isNotEmpty()
        setContent {
            MeetSnapTheme {
                if (configured) {
                    val vm: AppViewModel by viewModels()
                    MeetSnapRoot(vm)
                } else {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Firebase is not configured. Add app/google-services.json from the Firebase console and rebuild (see README).")
                    }
                }
            }
        }
    }
}
