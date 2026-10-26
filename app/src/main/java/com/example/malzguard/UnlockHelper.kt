package com.example.malzguard

import android.app.Activity
import android.os.Bundle

class UnlockHelper : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        try { finish() } catch (e: Exception) {}
    }
}
