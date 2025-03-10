package com.sap.gigya_flutter_plugin

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.gigya.android.sdk.*
import com.gigya.android.sdk.account.models.GigyaAccount
import com.gigya.android.sdk.api.GigyaApiResponse
import com.gigya.android.sdk.api.IApiRequestFactory
import com.gigya.android.sdk.auth.GigyaAuth
import com.gigya.android.sdk.auth.GigyaOTPCallback
import com.gigya.android.sdk.auth.resolvers.IGigyaOtpResult
import com.gigya.android.sdk.interruption.IPendingRegistrationResolver
import com.gigya.android.sdk.interruption.link.ILinkAccountsResolver
import com.gigya.android.sdk.network.GigyaError
import com.gigya.android.sdk.session.SessionInfo
import com.gigya.android.sdk.ui.plugin.GigyaPluginEvent
import com.gigya.android.sdk.utils.CustomGSONDeserializer
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.*

class GigyaSDKWrapper<T : GigyaAccount>(application: Application, accountObj: Class<T>) {

    private var sdk: Gigya<T>

    private var sdkAuth: GigyaAuth

    private var resolverHelper: ResolverHelper = ResolverHelper()

    private var currentResult: MethodChannel.Result? = null

    private val gson = GsonBuilder().registerTypeAdapter(
        object : TypeToken<Map<String?, Any?>?>() {}.type,
        CustomGSONDeserializer()
    ).create()

    init {
        Gigya.setApplication(application)
        sdk = Gigya.getInstance(accountObj)
        sdkAuth = GigyaAuth.getInstance()

        try {
            val pInfo: PackageInfo =
                application.packageManager.getPackageInfo(application.packageName, 0)
            val version: String = pInfo.versionName
            val ref: IApiRequestFactory = Gigya.getContainer().get(IApiRequestFactory::class.java)
            ref.setSDK("flutter_${version}_android_${(Gigya.VERSION).toLowerCase(Locale.ENGLISH)}")
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
    }

    //region REQUEST INTERFACING

    /**
     * Send general/antonymous request.
     */
    fun sendRequest(arguments: Any, channelResult: MethodChannel.Result) {
        val endpoint: String? = (arguments as Map<*, *>)["endpoint"] as String?
        if (endpoint == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        val parameters: Map<String, Any>? = arguments["parameters"] as Map<String, Any>?
        if (parameters == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        sdk.send(endpoint, parameters, object : GigyaCallback<GigyaApiResponse>() {
            override fun onSuccess(p0: GigyaApiResponse?) {
                p0?.let {
                    channelResult.success(it.asJson())
                } ?: channelResult.notImplemented()
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    channelResult.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: channelResult.notImplemented()
            }

        })
    }

    /**
     * Login using credentials (loginId/password combination with optional parameter map).
     */
    fun loginWithCredentials(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        val loginId: String? = (arguments as Map<*, *>)["loginId"] as String?
        if (loginId == null) {
            currentResult!!.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        val password: String? = arguments["password"] as String?
        if (password == null) {
            currentResult!!.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        val parameters: Map<String, Any>? = arguments["parameters"] as Map<String, Any>?
        val loginParams = mutableMapOf<String, Any>("loginID" to loginId, "password" to password)
        if (parameters != null) {
            loginParams.putAll(parameters)
        }
        sdk.login(loginParams, object : GigyaLoginCallback<T>() {

            override fun onSuccess(p0: T) {
                resolverHelper.clear()
                val mapped = mapObject(p0)
                currentResult!!.success(mapped)
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    currentResult!!.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: currentResult!!.notImplemented()
            }

            override fun onConflictingAccounts(
                response: GigyaApiResponse,
                resolver: ILinkAccountsResolver
            ) {
                resolverHelper.linkAccountResolver = resolver
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

            override fun onPendingRegistration(
                response: GigyaApiResponse,
                resolver: IPendingRegistrationResolver
            ) {
                resolverHelper.pendingRegistrationResolver = resolver
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

            override fun onPendingVerification(response: GigyaApiResponse, regToken: String?) {
                resolverHelper.regToken = regToken
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }
        })
    }

    /**
     * Register a new user using credentials (email/password combination with optional parameter map).
     */
    fun registerWithCredentials(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult;
        val email: String? = (arguments as Map<*, *>)["email"] as String?
        if (email == null) {
            currentResult!!.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        val password: String? = arguments["password"] as String?
        if (password == null) {
            currentResult!!.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        val parameters: MutableMap<String, Any> =
            arguments["parameters"] as MutableMap<String, Any>?
                ?: mutableMapOf()
        sdk.register(email, password, parameters!!, object : GigyaLoginCallback<T>() {
            override fun onSuccess(p0: T) {
                resolverHelper.clear()
                val mapped = mapObject(p0)
                currentResult!!.success(mapped)
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    currentResult!!.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: currentResult!!.notImplemented()
            }

            override fun onConflictingAccounts(
                response: GigyaApiResponse,
                resolver: ILinkAccountsResolver
            ) {
                resolverHelper.linkAccountResolver = resolver
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

            override fun onPendingRegistration(
                response: GigyaApiResponse,
                resolver: IPendingRegistrationResolver
            ) {
                resolverHelper.pendingRegistrationResolver = resolver
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

            override fun onPendingVerification(response: GigyaApiResponse, regToken: String?) {
                resolverHelper.regToken = regToken
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

        })
    }

    /**
     * Check login status.
     */
    fun isLoggedIn(channelResult: MethodChannel.Result) {
        val loginState = sdk.isLoggedIn
        channelResult.success(loginState)
    }

    /**
     * Request active account.
     */
    fun getAccount(arguments: Any, channelResult: MethodChannel.Result) {
        val invalidate: Boolean = (arguments as Map<*, *>)["invalidate"] as Boolean? ?: false
        val parameters: MutableMap<String, Any> =
            arguments["parameters"] as MutableMap<String, Any>?
                ?: mutableMapOf()
        sdk.getAccount(invalidate, parameters, object : GigyaCallback<T>() {
            override fun onSuccess(p0: T) {
                val mapped = mapObject(p0)
                channelResult.success(mapped)
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    channelResult.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: channelResult.notImplemented()
            }

        })
    }

    /**
     * Update account information
     */
    fun setAccount(arguments: Any, channelResult: MethodChannel.Result) {
        val account: MutableMap<String, Any>? =
            (arguments as Map<*, *>)["account"] as MutableMap<String, Any>?
        if (account == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        sdk.setAccount(account, object : GigyaCallback<T>() {
            override fun onSuccess(p0: T) {
                val mapped = mapObject(p0)
                channelResult.success(mapped)
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    channelResult.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: channelResult.notImplemented()
            }

        })
    }

    /**
     * Forgot password.
     */
    fun forgotPassword(arguments: Any, channelResult: MethodChannel.Result) {
        val loginId: String? = (arguments as Map<*, *>)["loginId"] as String?
        if (loginId == null) {
            currentResult!!.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        sdk.forgotPassword(loginId, object : GigyaCallback<GigyaApiResponse>() {
            override fun onSuccess(p0: GigyaApiResponse?) {
                channelResult.success(p0?.asMap())
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    channelResult.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: channelResult.notImplemented()
            }

        });
    }

    /**
     * Init SDK.
     */
    fun initSdk(arguments: Any, channelResult: MethodChannel.Result) {
        val apiKey: String? = (arguments as Map<*, *>)["apiKey"] as String?
        if (apiKey == null) {
            channelResult.success(mapOf("success" to false))

            currentResult!!.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        val apiDomain: String? = (arguments as Map<*, *>)["apiDomain"] as String?
        if (apiDomain == null) {
            channelResult.success(mapOf("success" to false))

            currentResult!!.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        sdk.init(apiKey, apiDomain);

        channelResult.success(mapOf("success" to true))
    }

    /**
     * Logout of existing session.
     */
    fun logOut(channelResult: MethodChannel.Result) {
        sdk.logout(object : GigyaCallback<GigyaApiResponse>() {
            override fun onSuccess(p0: GigyaApiResponse?) {
                channelResult.success(null)
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    channelResult.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: channelResult.notImplemented()
            }

        });
    }

    /**
     * Get current session
     */
    fun getSession(channelResult: MethodChannel.Result) {
        val session = sdk.session
        if (session != null) {
            channelResult.success(mapObject(session))
        } else {
            channelResult.success(null)
        }
    }

    /**
     * Manually set the session.
     * Will overwrite current session.
     */
    fun setSession(arguments: Any, channelResult: MethodChannel.Result) {
        val argumentMap: MutableMap<String, Any>? = arguments as MutableMap<String, Any>?
        if (argumentMap != null) {
            val sessionInfo = SessionInfo(
                argumentMap["sessionSecret"] as String,
                argumentMap["sessionToken"] as String,
                (argumentMap["expires_in"] as Double).toLong()
            )
            sdk.setSession(sessionInfo)
            channelResult.success(null)
            return
        }
        channelResult.error(
            MISSING_PARAMETER_ERROR,
            MISSING_PARAMETER_MESSAGE,
            mapOf<String, Any>()
        )
    }

    /**
     * Mobile sso initiator using CLP.
     */
    fun sso(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        val argumentMap: MutableMap<String, Any>? = arguments as MutableMap<String, Any>?
        sdk.login(GigyaDefinitions.Providers.SSO, argumentMap, object : GigyaLoginCallback<T>() {
            override fun onSuccess(p0: T) {
                val mapped = mapObject(p0)
                resolverHelper.clear()
                currentResult!!.success(mapped)
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    currentResult!!.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: currentResult!!.notImplemented()
            }

            override fun onOperationCanceled() {
                currentResult!!.error(CANCELED_ERROR, CANCELED_ERROR_MESSAGE, null)
            }

            override fun onConflictingAccounts(
                response: GigyaApiResponse,
                resolver: ILinkAccountsResolver
            ) {
                resolverHelper.linkAccountResolver = resolver
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

            override fun onPendingRegistration(
                response: GigyaApiResponse,
                resolver: IPendingRegistrationResolver
            ) {
                resolverHelper.pendingRegistrationResolver = resolver
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

            override fun onPendingVerification(response: GigyaApiResponse, regToken: String?) {
                resolverHelper.regToken = regToken
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

        })
    }

    /**
     * Social login with given provider & provider sessions.
     */
    fun socialLogin(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        val provider: String? = (arguments as Map<*, *>)["provider"] as String?
        if (provider == null) {
            currentResult!!.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        val parameters: MutableMap<String, Any> =
            arguments["parameters"] as MutableMap<String, Any>?
                ?: mutableMapOf()
        sdk.login(provider, parameters, object : GigyaLoginCallback<T>() {
            override fun onSuccess(p0: T) {
                val mapped = mapObject(p0)
                resolverHelper.clear()
                currentResult!!.success(mapped)
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    currentResult!!.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: currentResult!!.notImplemented()
            }

            override fun onOperationCanceled() {
                currentResult!!.error(CANCELED_ERROR, CANCELED_ERROR_MESSAGE, null)
            }

            override fun onConflictingAccounts(
                response: GigyaApiResponse,
                resolver: ILinkAccountsResolver
            ) {
                resolverHelper.linkAccountResolver = resolver
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

            override fun onPendingRegistration(
                response: GigyaApiResponse,
                resolver: IPendingRegistrationResolver
            ) {
                resolverHelper.pendingRegistrationResolver = resolver
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

            override fun onPendingVerification(response: GigyaApiResponse, regToken: String?) {
                resolverHelper.regToken = regToken
                currentResult!!.error(
                    response.errorCode.toString(),
                    response.errorDetails,
                    response.asMap()
                )
            }

        })
    }

    /**
     * Add social connection to active session.
     */
    fun addConnection(arguments: Any, channelResult: MethodChannel.Result) {
        val provider: String? = (arguments as Map<*, *>)["provider"] as String?
        if (provider == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        sdk.addConnection(provider, object : GigyaLoginCallback<T>() {
            override fun onSuccess(p0: T) {
                val mapped = mapObject(p0)
                channelResult.success(mapped)
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    channelResult.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: channelResult.notImplemented()
            }

            override fun onOperationCanceled() {
                channelResult.error(CANCELED_ERROR, CANCELED_ERROR_MESSAGE, null)
            }

        })
    }

    /**
     * Remove social connection.
     */
    fun removeConnection(arguments: Any, channelResult: MethodChannel.Result) {
        val provider: String? = (arguments as Map<*, *>)["provider"] as String?
        if (provider == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        sdk.removeConnection(provider, object : GigyaCallback<GigyaApiResponse>() {
            override fun onSuccess(p0: GigyaApiResponse?) {
                p0?.let {
                    val mapped = gson.fromJson<Map<String, Any>>(
                        it.asJson(),
                        object : TypeToken<Map<String, Any>>() {}.type
                    )
                    channelResult.success(mapped)
                } ?: channelResult.notImplemented()
            }

            override fun onError(p0: GigyaError?) {
                p0?.let {
                    channelResult.error(
                        p0.errorCode.toString(),
                        p0.localizedMessage,
                        mapJson(p0.data)
                    )
                } ?: channelResult.notImplemented()
            }

        })
    }

    //endregion

    //region SCREENSETS

    private var screenSetsEventsSink: EventChannel.EventSink? = null
    private var screenSetEventsChannel: EventChannel? = null
    private var screenSetsEventsHandler: EventChannel.StreamHandler? = null;

    /**
     * Trigger embedded web screen sets.
     */
    fun showScreenSet(
        arguments: Any,
        channelResult: MethodChannel.Result,
        messenger: BinaryMessenger?
    ) {
        if (messenger == null) {
            channelResult.error(GENERAL_ERROR, GENERAL_ERROR_MESSAGE, mapOf<String, Any>())
            return
        }
        val screenSet: String? = (arguments as Map<*, *>)["screenSet"] as String?
        if (screenSet == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
            return
        }
        val parameters: MutableMap<String, Any> =
            arguments["parameters"] as MutableMap<String, Any>?
                ?: mutableMapOf()

        // Set events channel & handler.
        screenSetEventsChannel = EventChannel(messenger, "screensetEvents")
        screenSetsEventsHandler = object : EventChannel.StreamHandler {
            override fun onListen(p0: Any?, sink: EventChannel.EventSink?) {
                screenSetsEventsSink = sink
            }

            override fun onCancel(p0: Any?) {
                screenSetEventsChannel = null
            }
        }
        screenSetEventsChannel!!.setStreamHandler(screenSetsEventsHandler)

        sdk.showScreenSet(screenSet, true, parameters, object : GigyaPluginCallback<T>() {
            override fun onError(event: GigyaPluginEvent?) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onError",
                        "data" to event!!.eventMap
                    )
                )
            }

            override fun onCanceled() {
                screenSetsEventsSink?.error("200001", "Operation canceled", null)
                screenSetsEventsHandler = null
                screenSetEventsChannel = null
                screenSetsEventsSink = null
            }

            override fun onHide(event: GigyaPluginEvent, reason: String?) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onHide",
                        "reason" to reason!!,
                        "data" to event.eventMap
                    )
                )
                screenSetsEventsHandler = null
                screenSetEventsChannel = null
                screenSetsEventsSink = null
            }

            override fun onLogin(accountObj: T) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onLogin",
                        "data" to mapObject(accountObj)
                    )
                )
            }

            override fun onLogout() {
                screenSetsEventsSink?.success(mapOf("event" to "onLogout"))
            }

            override fun onConnectionAdded() {
                screenSetsEventsSink?.success(mapOf("event" to "onConnectionAdded"))
            }

            override fun onConnectionRemoved() {
                screenSetsEventsSink?.success(mapOf("event" to "onConnectionRemoved"))
            }

            override fun onBeforeScreenLoad(event: GigyaPluginEvent) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onBeforeScreenLoad",
                        "data" to event.eventMap
                    )
                )
            }

            override fun onAfterScreenLoad(event: GigyaPluginEvent) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onAfterScreenLoad",
                        "data" to event.eventMap
                    )
                )
            }

            override fun onBeforeValidation(event: GigyaPluginEvent) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onBeforeValidation",
                        "data" to event.eventMap
                    )
                )
            }

            override fun onAfterValidation(event: GigyaPluginEvent) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onAfterValidation",
                        "data" to event.eventMap
                    )
                )
            }

            override fun onBeforeSubmit(event: GigyaPluginEvent) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onBeforeSubmit",
                        "data" to event.eventMap
                    )
                )
            }

            override fun onSubmit(event: GigyaPluginEvent) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onSubmit",
                        "data" to event.eventMap
                    )
                )
            }

            override fun onAfterSubmit(event: GigyaPluginEvent) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onAfterSubmit",
                        "data" to event.eventMap
                    )
                )
            }

            override fun onFieldChanged(event: GigyaPluginEvent) {
                screenSetsEventsSink?.success(
                    mapOf(
                        "event" to "onFieldChanged",
                        "data" to event.eventMap
                    )
                )
            }
        })

        // Return void result. Streaming channel will handled plugin events.
        channelResult.success(null)
    }

    //endregion

    //region FIDO

    /**
     * Fido2/WebAuthn register.
     */
    fun webAuthnRegister(
        resultLauncher: ActivityResultLauncher<IntentSenderRequest>,
        channelResult: MethodChannel.Result
    ) {
        sdk.WebAuthn().register(
            resultLauncher,
            object : GigyaCallback<GigyaApiResponse>() {
                override fun onSuccess(obj: GigyaApiResponse?) {
                    channelResult.success(obj?.asJson())
                }

                override fun onError(error: GigyaError?) {
                    error?.let {
                        channelResult.error(
                            error.errorCode.toString(),
                            error.localizedMessage,
                            mapJson(error.data)
                        )
                    } ?: channelResult.notImplemented()
                }

            }
        )
    }

    /**
     * Fido2/WebAuthn login.
     */
    fun webAuthnLogin(
        resultLauncher: ActivityResultLauncher<IntentSenderRequest>,
        channelResult: MethodChannel.Result
    ) {
        sdk.WebAuthn().login(
            resultLauncher,
            object : GigyaLoginCallback<T>() {
                override fun onSuccess(obj: T) {
                    val mapped = mapObject(obj)
                    channelResult.success(mapped)
                }

                override fun onError(error: GigyaError?) {
                    error?.let {
                        channelResult.error(
                            error.errorCode.toString(),
                            error.localizedMessage,
                            mapJson(error.data)
                        )
                    } ?: channelResult.notImplemented()
                }

            }
        )
    }

    /**
     * Fido2/WebAuthn revoke.
     */
    fun webAuthnRevoke(channelResult: MethodChannel.Result) {
        sdk.WebAuthn().revoke(
            object : GigyaCallback<GigyaApiResponse>() {
                override fun onSuccess(obj: GigyaApiResponse?) {
                    channelResult.success(obj?.asJson())
                }

                override fun onError(error: GigyaError?) {
                    error?.let {
                        channelResult.error(
                            error.errorCode.toString(),
                            error.localizedMessage,
                            mapJson(error.data)
                        )
                    } ?: channelResult.notImplemented()
                }

            }
        )
    }

    //endregion

    //region OTP

    /**
     * Login via phone OTP.
     */
    fun otpLogin(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        val phoneNumber: String? = (arguments as Map<*, *>)["phone"] as String?
        if (phoneNumber == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
        }
        var parameters: Map<String, Any>? = arguments["parameters"] as Map<String, Any>?
        if (parameters == null) {
            parameters = mapOf()
        }
        sdkAuth.otp.phoneLogin(
            phoneNumber!!, parameters, object : GigyaOTPCallback<T>() {

                override fun onPendingOTPVerification(
                    response: GigyaApiResponse,
                    resolver: IGigyaOtpResult
                ) {
                    resolverHelper.otpResolver = resolver
                    currentResult!!.success(mapObject(response))
                }

                override fun onSuccess(obj: T) {
                    resolverHelper.clear()
                    val mapped = mapObject(obj)
                    currentResult!!.success(mapped)
                }

                override fun onError(error: GigyaError?) {
                    error?.let {
                        currentResult!!.error(
                            error.errorCode.toString(),
                            error.localizedMessage,
                            mapJson(error.data)
                        )
                    } ?: channelResult.notImplemented()
                }

            }
        )
    }

    /**
     * Update phone number using OTP verification for existing user.
     */
    fun otpUpdate(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        val phoneNumber: String? = (arguments as Map<*, *>)["phone"] as String?
        if (phoneNumber == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
        }
        var parameters: Map<String, Any>? = arguments["parameters"] as Map<String, Any>?
        if (parameters == null) {
            parameters = mapOf()
        }
        sdkAuth.otp.phoneUpdate(phoneNumber!!, parameters, object : GigyaOTPCallback<T>() {
            override fun onPendingOTPVerification(
                response: GigyaApiResponse,
                resolver: IGigyaOtpResult
            ) {
                resolverHelper.otpResolver = resolver
                currentResult!!.success(mapObject(response))
            }

            override fun onSuccess(obj: T) {
                resolverHelper.clear()
                val mapped = mapObject(obj)
                currentResult!!.success(mapped)
            }

            override fun onError(error: GigyaError?) {
                error?.let {
                    currentResult!!.error(
                        error.errorCode.toString(),
                        error.localizedMessage,
                        mapJson(error.data)
                    )
                } ?: channelResult.notImplemented()
            }

        })
    }

    /**
     * Verify phone OTP code.
     */
    fun otpVerify(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        val code: String? = (arguments as Map<*, *>)["code"] as String?
        if (code == null) {
            channelResult.error(
                MISSING_PARAMETER_ERROR,
                MISSING_PARAMETER_MESSAGE,
                mapOf<String, Any>()
            )
        }
        resolverHelper.otpResolver?.verify(code!!)
    }

    //endregion

    //region RESOLVERS

    /**
     * Link account - handler for fetching conflicting accounts from current interruption state.
     */
    fun resolveGetConflictingAccounts(channelResult: MethodChannel.Result) {
        resolverHelper.linkAccountResolver?.let { resolver ->
            val conflictingAccounts = resolver.conflictingAccounts
            channelResult.success(mapObject(conflictingAccounts));
        } ?: channelResult.notImplemented()

    }

    /**
     * Link account - resolving link to site.
     */
    fun resolveLinkToSite(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        resolverHelper.linkAccountResolver?.let { resolver ->
            val loginId: String? = (arguments as Map<*, *>)["loginId"] as String?
            if (loginId == null) {
                channelResult.error(
                    MISSING_PARAMETER_ERROR,
                    MISSING_PARAMETER_MESSAGE,
                    mapOf<String, Any>()
                )
                return
            }
            val password: String? = (arguments as Map<*, *>)["password"] as String?
            if (password == null) {
                channelResult.error(
                    MISSING_PARAMETER_ERROR,
                    MISSING_PARAMETER_MESSAGE,
                    mapOf<String, Any>()
                )
                return
            }
            resolver.linkToSite(loginId, password)

        } ?: channelResult.notImplemented()
    }

    /**
     * Link account - resolving link to social.
     */
    fun resolveLinkToSocial(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        resolverHelper.linkAccountResolver?.let { resolver ->
            val provider: String? = (arguments as Map<*, *>)["provider"] as String?
            if (provider == null) {
                channelResult.error(
                    MISSING_PARAMETER_ERROR,
                    MISSING_PARAMETER_MESSAGE,
                    mapOf<String, Any>()
                )
                return
            }
            resolver.linkToSocial(provider)

        } ?: channelResult.notImplemented()
    }

    /**
     * Pending registration - resolving missing account data.
     */
    fun resolveSetAccount(arguments: Any, channelResult: MethodChannel.Result) {
        currentResult = channelResult
        resolverHelper.pendingRegistrationResolver?.let { resolver ->
            val data: Map<String, Any>? = arguments as Map<String, Any>
            if (data == null) {
                channelResult.error(
                    MISSING_PARAMETER_ERROR,
                    MISSING_PARAMETER_MESSAGE,
                    mapOf<String, Any>()
                )
                return
            }
            resolver.setAccount(data)
        } ?: channelResult.notImplemented()
    }

    //endregion

    /**
     * Map typed object to a Map<String, Any> object in order to pass on to
     * the method channel response.
     */
    private fun <V> mapObject(obj: V): Map<String, Any> {
        val jsonString = gson.toJson(obj)
        return gson.fromJson(jsonString, object : TypeToken<Map<String, Any>>() {}.type)
    }

    /**
     * Map a JSON string to a Map<String, Any> object in order to pass on to
     * the method channel response.
     */
    private fun mapJson(jsonString: String): Map<String, Any> {
        return gson.fromJson(jsonString, object : TypeToken<Map<String, Any>>() {}.type)

    }

    companion object {

        const val GENERAL_ERROR = "700"
        const val GENERAL_ERROR_MESSAGE = "general error"
        const val MISSING_PARAMETER_ERROR = "701"
        const val MISSING_PARAMETER_MESSAGE = "request parameter missing"
        const val CANCELED_ERROR = "702"
        const val CANCELED_ERROR_MESSAGE = "Operation canceled"
    }

}

class ResolverHelper {

    var linkAccountResolver: ILinkAccountsResolver? = null
    var pendingRegistrationResolver: IPendingRegistrationResolver? = null
    var otpResolver: IGigyaOtpResult? = null
    var regToken: String? = null

    fun clear() {
        linkAccountResolver = null
        pendingRegistrationResolver = null
        otpResolver = null
        regToken = null
    }
}