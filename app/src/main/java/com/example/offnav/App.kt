package com.example.offnav

import android.app.Application
import com.example.offnav.di.AppContainer
class App : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}