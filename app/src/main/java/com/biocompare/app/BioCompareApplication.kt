package com.biocompare.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt entry point. The annotation generates the dependency graph and the
 * generated component is attached automatically to all @AndroidEntryPoint
 * activities, services, and fragments.
 */
@HiltAndroidApp
class BioCompareApplication : Application()
