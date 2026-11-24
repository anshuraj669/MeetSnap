package com.meetsnap.app

import android.app.Application
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.initialize

class MeetSnapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.initializeApp(this) == null) {
            Log.e("MeetSnap", "Firebase not configured: add app/google-services.json.")
            return
        }
        Firebase.initialize(this)
        // App Check is required by Firebase AI Logic. Debug builds use the debug provider:
        // register the token (logcat tag DebugAppCheckProvider) in Firebase console > App Check.
        Firebase.appCheck.installAppCheckProviderFactory(
            if (BuildConfig.DEBUG || BuildConfig.APPCHECK_DEBUG) DebugAppCheckProviderFactory.getInstance()
            else PlayIntegrityAppCheckProviderFactory.getInstance(),
        )
    }
}
