package io.github.soclear.oneuix.hook

import android.content.Context
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridge.hookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.soclear.oneuix.data.Package
import io.github.soclear.oneuix.hook.util.HookConfig
import io.github.soclear.oneuix.hook.util.afterAttach
import io.github.soclear.oneuix.hook.util.getHookConfig
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod

object ShareLive {
    /**
     * 开启「与 Apple 设备分享」(Protocol X / CoA) 设置入口。
     *
     * Quick Share 通过 ProtocolXRepository.supportsProtocolX() 决定该入口是否可见，
     * 该方法在 One UI 7 (SEM_PLATFORM_INT < 170500) 以下或策略关闭时直接返回 Single.just(false)。
     * 这里将其整体替换为 Single.just(true)，绕过版本/策略/能力门控。
     */
    fun enableShareWithCoa(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.processName != Package.SHARE_LIVE) return
        afterAttach {
            val hookConfig = getHookConfig { getHookConfigFromDexKit() } ?: return@afterAttach
            try {
                val supportsProtocolXMethod =
                    DexMethod(hookConfig.supportsProtocolXMethod).getMethodInstance(classLoader)
                // supportsProtocolX 返回 RxJava3 Single<Boolean>，其静态 just(Object) 方法
                val singleJustMethod =
                    DexMethod(hookConfig.singleJustMethod).getMethodInstance(classLoader)
                hookMethod(supportsProtocolXMethod, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? =
                        singleJustMethod.invoke(null, true)
                })
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }

    @Serializable
    private data class ShareLiveHookConfig(
        override val versionCode: Long,
        val supportsProtocolXMethod: String,
        val singleJustMethod: String,
    ) : HookConfig

    private fun Context.getHookConfigFromDexKit(): ShareLiveHookConfig? {
        System.loadLibrary("dexkit")
        DexKitBridge.create(classLoader, true).use { bridge ->
            // 定位 ProtocolXRepository.supportsProtocolX(boolean)
            val supportsProtocolXMethodData = bridge.findMethod {
                matcher {
                    usingStrings(
                        "protocol_x_supported",
                        "[ProtocolXRepository] supportsProtocolX: policyPao.protocolXSupported=false",
                    )
                }
            }.singleOrNull() ?: return null

            // supportsProtocolX 的返回类型即 RxJava3 Single（被混淆），
            // 其类内唯一签名为 static (Object) -> ... 的方法即 Single.just()
            // 注意 just() 返回的是 Single 的具体实现子类，故不约束返回类型
            val singleClassName = supportsProtocolXMethodData.returnTypeName
            val singleJustMethodData = bridge.findMethod {
                matcher {
                    declaredClass(singleClassName)
                    modifiers(java.lang.reflect.Modifier.STATIC)
                    paramTypes(listOf("java.lang.Object"))
                }
            }.singleOrNull() ?: return null

            return ShareLiveHookConfig(
                versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode,
                supportsProtocolXMethod = supportsProtocolXMethodData.toDexMethod().serialize(),
                singleJustMethod = singleJustMethodData.toDexMethod().serialize(),
            )
        }
    }
}
