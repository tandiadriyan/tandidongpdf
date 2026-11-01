package com.tandidong.pdf

import android.app.Application
import android.util.Log
import com.tandidong.pdf.core.CoreLog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class TandidongApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        CoreLog.sink = { level, message, error ->
            when (level) {
                'E' -> Log.e("TandidongCore", message, error)
                'W' -> Log.w("TandidongCore", message, error)
                else -> Log.d("TandidongCore", message)
            }
        }
    }
}
