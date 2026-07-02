package io.github.soclear.oneuix.hook

import android.content.Context
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.soclear.oneuix.data.Package
import io.github.soclear.oneuix.hook.util.HookConfig
import io.github.soclear.oneuix.hook.util.afterAttach
import io.github.soclear.oneuix.hook.util.getHookConfig
import io.github.soclear.oneuix.hook.util.longVersionCode
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Modifier

object ShareLive {
    // 仅属于 ProtocolXRepository.supportsProtocolX(boolean) 的唯一字符串特征
    private const val SUPPORTS_PROTOCOL_X_STRING =
        "[ProtocolXRepository] supportsProtocolX: policyPao.protocolXSupported=false"

    /**
     * 让"与 Apple 设备共享"(Share with CoA / Protocol X) 开关显示且可开启。
     *
     * 原理：supportsProtocolX(boolean) 是该功能是否可用的总闸门，返回 RxJava Single<Boolean>。
     * 把它替换为恒返回宿主自身的 Single.just(true)，即可绕过 FEATURE flag、服务端策略、
     * isMoseySupported、mcf 等全部检查，让设置项出现。
     *
     * 注意：实际与 Apple 设备通信仍依赖 GMS Nearby 组件(com.google.android.mosey)，
     * 国行机型缺少该组件时可能仅能显示开关而无法真正传输。
     */
    fun enableShareWithApple(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.packageName != Package.SHARE_LIVE) {
            return
        }
        afterAttach {
            val hookConfig = getHookConfig { getHookConfigFromDexKit() } ?: return@afterAttach
            try {
                val supportsProtocolXMethod =
                    DexMethod(hookConfig.supportsProtocolXMethod).getMethodInstance(classLoader)
                val singleJustMethod =
                    DexMethod(hookConfig.singleJustMethod).getMethodInstance(classLoader)

                XposedBridge.hookMethod(
                    supportsProtocolXMethod,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any =
                            // 用宿主自身混淆过的 Single.just(Boolean.TRUE)
                            singleJustMethod.invoke(null, java.lang.Boolean.TRUE)!!
                    }
                )
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
            // 1. 定位 supportsProtocolX(boolean)：唯一字符串 + 单 boolean 参
            val supportsProtocolXMethodData = bridge.findMethod {
                matcher {
                    paramCount = 1
                    paramTypes("boolean")
                    usingStrings(SUPPORTS_PROTOCOL_X_STRING)
                }
            }.singleOrNull() ?: return null

            // supportsProtocolX 的返回类型即混淆的 RxJava Single 类，just 方法声明在该类内
            val singleClassName = supportsProtocolXMethodData.returnTypeName

            // 2. 定位 Single.just(Object)：声明类=Single类 + static + 单 Object 参 + "item is null"
            //    (用 declaredClass 而非 returnType，因 just() 返回的是 Single 的子类)
            val singleJustMethodData = bridge.findMethod {
                matcher {
                    declaredClass(singleClassName)
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    paramCount = 1
                    paramTypes("java.lang.Object")
                    usingStrings("item is null")
                }
            }.singleOrNull() ?: return null

            return ShareLiveHookConfig(
                versionCode = longVersionCode,
                supportsProtocolXMethod = supportsProtocolXMethodData.toDexMethod().serialize(),
                singleJustMethod = singleJustMethodData.toDexMethod().serialize(),
            )
        }
    }
}
