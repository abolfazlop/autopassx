package dev.autopassx.lsposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Modern LibXposed API 102 entry point.
 * This entry intentionally does not inspect text fields or collect credentials.
 * Only explicitly scoped, app-specific integrations may be added later.
 */
class AutoPassXposedModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "AutoPass X loaded in ${param.processName}; framework=$frameworkName API=$apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log(Log.INFO, TAG, "Scoped process ready: ${param.packageName}")
    }

    companion object { private const val TAG = "AutoPassX" }
}