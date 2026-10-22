package com.offgrid.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.offgrid.app.core.notification.NotificationHelper

class OffgridApplication : Application() {

    companion object {
        @Volatile
        var currentActivePeerId: String? = null

        @Volatile
        private var activityReferences = 0

        @Volatile
        private var isActivityChangingConfigurations = false

        fun isAppInForeground(): Boolean = activityReferences > 0

        fun getEngine(context: Context): OffgridEngine {
            return OffgridEngine.getInstance(context.applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize notification channels for background service, incoming messages, and transfers
        NotificationHelper.initChannels(this)

        // 2. Track activity lifecycle to determine foreground/background state
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                if (++activityReferences == 1 && !isActivityChangingConfigurations) {
                    android.util.Log.i("OffgridApplication", "[LIFECYCLE] App entered FOREGROUND")
                }
            }

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                isActivityChangingConfigurations = activity.isChangingConfigurations
                if (--activityReferences <= 0 && !isActivityChangingConfigurations) {
                    activityReferences = 0
                    android.util.Log.i("OffgridApplication", "[LIFECYCLE] App entered BACKGROUND")
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        })

        // 3. Eagerly initialize the persistent OffgridEngine singleton
        OffgridEngine.getInstance(this)
    }
}
