package io.github.soclear.oneuix.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridge.hookMethod
import de.robv.android.xposed.XposedHelpers.callStaticMethod
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.XposedHelpers.findClassIfExists
import de.robv.android.xposed.XposedHelpers.findMethodExactIfExists
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.soclear.oneuix.data.Package
import kotlin.math.log


object Settings {
    private val shareLiveForcedBooleanPrefs = setOf(
        "share_with_coa_option",
        "protocol_x_supported",
        "discovery_additional_feature",
        "china_p2p_component",
        "visibility_temporary_option",
        "scan_my_device_visibility_off"
    )

    fun showPackageInfo(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.packageName != Package.SETTINGS) return
        val callback = object : XC_MethodHook() {
            @SuppressLint("DiscouragedApi", "SetTextI18n")
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val header = getObjectField(param.thisObject, "mHeader")
                    val mRootView = getObjectField(header, "mRootView") as View
                    val identifier = mRootView.resources.getIdentifier(
                        "entity_header_summary", "id", Package.SETTINGS
                    )
                    val packageInfo = param.args[0] as PackageInfo
                    val versionName = packageInfo.versionName
                    val versionCode = packageInfo.longVersionCode
                    val packageName = packageInfo.packageName
                    mRootView.findViewById<TextView>(identifier).apply {
                        text = "$text $versionName ($versionCode)\n$packageName"
                        setTextIsSelectable(true)
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        }
        try {
            findAndHookMethod(
                "com.android.settings.applications.appinfo.AppHeaderViewPreferenceController",
                loadPackageParam.classLoader,
                "setAppLabelAndIcon",
                PackageInfo::class.java,
                $$"com.android.settingslib.applications.ApplicationsState$AppEntry",
                callback
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    // 支持任意字体
    fun supportAnyFont(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.packageName != Package.SETTINGS) return
        try {
            findAndHookMethod(
                "com.samsung.android.settings.display.SecDisplayUtils",
                loadPackageParam.classLoader,
                "isInvalidFont",
                Context::class.java,
                String::class.java,
                returnConstant(false)
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun showMoreBatteryInfo(loadPackageParam: LoadPackageParam) {
        // res/xml/sec_battery_info_settings.xml
        // com.samsung.android.settings.deviceinfo.batteryinfo
        if (loadPackageParam.packageName != Package.SETTINGS) return
        try {
            findAndHookMethod(
                "com.samsung.android.settings.deviceinfo.batteryinfo.BatteryRegulatoryPreferenceController",
                loadPackageParam.classLoader,
                "getAvailabilityStatus",
                returnConstant(0)
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun showForcePeakRefreshRatePreference(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.packageName != Package.SETTINGS) return
        try {
            findAndHookMethod(
                "com.android.settings.development.ForcePeakRefreshRatePreferenceController",
                loadPackageParam.classLoader,
                "isAvailable",
                returnConstant(true)
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun supportOutdoorMode(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.packageName != Package.SETTINGS) return
        try {
            findAndHookMethod(
                "com.samsung.android.settings.display.controller.SecOutDoorModePreferenceController",
                loadPackageParam.classLoader,
                "isAvailable",
                returnConstant(true)
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        try {
            findMethodExactIfExists(
                "com.samsung.android.settings.Rune",
                loadPackageParam.classLoader,
                "supportOutdoorMode",
                Context::class.java
            )?.let {
                hookMethod(it, returnConstant(true))
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun supportAutoPowerOnOff(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.packageName != Package.SETTINGS) return

        findAndHookMethod(
            "com.samsung.android.feature.SemFloatingFeature",
            loadPackageParam.classLoader,
            "getBoolean",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == "SEC_FLOATING_FEATURE_SETTINGS_SUPPORT_AUTO_POWER_ON_OFF") {
                        param.result = true
                    }
                }
            }
        )

        findAndHookMethod(
            "com.samsung.android.settings.general.AutoPowerOnOffPreferenceController",
            loadPackageParam.classLoader,
            "isSupportAutoPowerOnOff",
            returnConstant(true)
        )

        val shouldSpoofChinaModel = ThreadLocal<Boolean>().apply { set(false) }

        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                shouldSpoofChinaModel.set(true)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                shouldSpoofChinaModel.set(false)
            }
        }

        findAndHookMethod(
            "com.samsung.android.settings.autopoweronoff.AutoPowerOnOffReceiver",
            loadPackageParam.classLoader,
            "onReceive",
            Context::class.java,
            Intent::class.java,
            callback
        )

        findAndHookMethod(
            "com.samsung.android.settings.autopoweronoff.AutoPowerOnOffSettings$2",
            loadPackageParam.classLoader,
            "resetSettings",
            Context::class.java,
            callback
        )

        findAndHookMethod(
            "com.samsung.android.settings.Rune",
            loadPackageParam.classLoader,
            "isChinaModel",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (shouldSpoofChinaModel.get() == true) {
                        param.setResult(true)
                    }
                }
            }
        )
    }

    fun spoofPhoneStatusAsOfficial(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.processName != Package.SETTINGS) return
        try {
            findAndHookMethod(
                "com.samsung.android.settings.deviceinfo.SecDeviceInfoUtils",
                loadPackageParam.classLoader,
                "isPhoneStatusUnlocked",
                returnConstant(false)
            )
            findAndHookMethod(
                "com.samsung.android.settings.deviceinfo.SecDeviceInfoUtils",
                loadPackageParam.classLoader,
                "checkRootingCondition",
                returnConstant(false)
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun shareWithAppleDevices(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.packageName != Package.SHARELIVE) return

        hookShareLivePreferences(loadPackageParam)
        hookShareLiveBooleanMethod(loadPackageParam, "lf.l", "c")
        hookShareLiveBooleanMethod(loadPackageParam, "ws.b", "a")
        hookShareLiveBooleanMethod(loadPackageParam, "ws.b", "b")

        hookShareLiveIntMethod(loadPackageParam, "sf.f", "a", 15)
        listOf("b", "c", "d", "e", "f", "h", "i", "j", "k", "l").forEach {
            hookShareLiveBooleanMethod(loadPackageParam, "sf.f", it)
        }

        hookProtocolXRepository(loadPackageParam)
        hookMcfProtocolXFeature(loadPackageParam)
    }

    private fun hookShareLivePreferences(loadPackageParam: LoadPackageParam) {
        try {
            findAndHookMethod(
                "android.app.SharedPreferencesImpl",
                null,
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key in shareLiveForcedBooleanPrefs) {
                            param.result = true
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        try {
            findAndHookMethod(
                "android.app.SharedPreferencesImpl\$EditorImpl",
                null,
                "putBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key in shareLiveForcedBooleanPrefs) {
                            param.args[1] = true
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

    }

    private fun hookProtocolXRepository(loadPackageParam: LoadPackageParam) {
        hookShareLiveMethod(
            loadPackageParam = loadPackageParam,
            className = "wf.n5",
            methodName = "e",
            Boolean::class.javaPrimitiveType!!,
            callback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = shareLiveTrueSingle(loadPackageParam)
                }
            }
        )

        hookShareLiveMethod(
            loadPackageParam = loadPackageParam,
            className = "wf.n5",
            methodName = "c",
            callback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = shareLiveTrueSingle(loadPackageParam)
                }
            }
        )

        hookShareLiveMethod(
            loadPackageParam = loadPackageParam,
            className = "lg.d",
            methodName = "apply",
            Any::class.java,
            callback = returnConstant(true)
        )

        hookShareLiveMethod(
            loadPackageParam = loadPackageParam,
            className = "d6.a",
            methodName = "apply",
            Any::class.java,
            Any::class.java,
            callback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (getIntField(param.thisObject, "f8819h") == 10) {
                        param.result = true
                    }
                }
            }
        )
    }

    private fun hookMcfProtocolXFeature(loadPackageParam: LoadPackageParam) {
        hookShareLiveMethod(
            loadPackageParam = loadPackageParam,
            className = "sf.l0",
            methodName = "call",
            callback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (getIntField(param.thisObject, "f24837h") == 10) {
                        param.result = true
                    }
                }
            }
        )

        findClassIfExists(
            "com.samsung.android.sdk.mdx.kit.compatibility.Feature",
            loadPackageParam.classLoader
        )?.let { featureClass ->
            try {
                findAndHookMethod(
                    featureClass,
                    "isSupported",
                    Context::class.java,
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args[1] == 128 || param.args[1] == 256) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }

    private fun hookShareLiveBooleanMethod(
        loadPackageParam: LoadPackageParam,
        className: String,
        methodName: String
    ) {
        hookShareLiveMethod(
            loadPackageParam = loadPackageParam,
            className = className,
            methodName = methodName,
            callback = returnConstant(true)
        )
    }

    private fun hookShareLiveIntMethod(
        loadPackageParam: LoadPackageParam,
        className: String,
        methodName: String,
        value: Int
    ) {
        hookShareLiveMethod(
            loadPackageParam = loadPackageParam,
            className = className,
            methodName = methodName,
            callback = returnConstant(value)
        )
    }

    private fun hookShareLiveMethod(
        loadPackageParam: LoadPackageParam,
        className: String,
        methodName: String,
        vararg parameterTypes: Any,
        callback: XC_MethodHook
    ) {
        try {
            findMethodExactIfExists(
                className,
                loadPackageParam.classLoader,
                methodName,
                *parameterTypes
            )?.let { hookMethod(it, callback) }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun shareLiveTrueSingle(loadPackageParam: LoadPackageParam): Any {
        val singleClass = findClassIfExists("yv.t", loadPackageParam.classLoader)
            ?: error("ShareLive yv.t not found")
        return callStaticMethod(singleClass, "i", true)
    }
}
