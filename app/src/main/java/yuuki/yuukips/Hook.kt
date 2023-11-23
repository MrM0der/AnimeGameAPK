package yuuki.yuukips

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.XModuleResources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.widget.*
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import yuuki.yuukips.Utils.dp2px
import yuuki.yuukips.Utils.isInit
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.regex.Pattern
import javax.net.ssl.*
import kotlin.system.exitProcess

class Hook {
    private val regex = Pattern.compile("http(s|)://.*?\\.(hoyoverse|mihoyo|yuanshen|mob|gptzfx|jp|api|fc|m|data|graph|facebook|devs|fd|hk4e-sdk-os)\\.com")

    private lateinit var server: String

    private lateinit var portSet: String

    private lateinit var modulePath: String
    private lateinit var moduleRes: XModuleResources
    private lateinit var windowManager: WindowManager

    private val activityList: ArrayList<Activity> = arrayListOf()
    private var activity: Activity
        get() {
            for (mActivity in activityList) {
                if (mActivity.isFinishing) {
                    activityList.remove(mActivity)
                } else {
                    return mActivity
                }
            }
            throw Throwable("Activity not found.")
        }
        set(value) {
            activityList.add(value)
        }

    private fun getDefaultSSLSocketFactory(): SSLSocketFactory {
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(), arrayOf<TrustManager>(DefaultTrustManager()), SecureRandom())
        }.socketFactory
    }

    private fun getDefaultHostnameVerifier(): HostnameVerifier {
        return DefaultHostnameVerifier()
    }

    class DefaultHostnameVerifier : HostnameVerifier {
        @SuppressLint("BadHostnameVerifier")
        override fun verify(p0: String?, p1: SSLSession?): Boolean {
            return true
        }

    }

    @SuppressLint("CustomX509TrustManager")
    private class DefaultTrustManager : X509TrustManager {

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {
        }

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate?>?, authType: String?) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return arrayOf()
        }
    }

    fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        moduleRes = XModuleResources.createInstance(modulePath, null)
        TrustMeAlready().initZygote()

        // default
        server = ""
    }

    private var startForceUrl = false
    private var startProxyList = false
    private lateinit var dialog: LinearLayout

    @SuppressLint("WrongConstant", "ClickableViewAccessibility")
    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if ((lpparam.packageName == "com.moe.yuukips32") || (lpparam.packageName == "com.yuuki.gi40cn") || (lpparam.packageName == "com.miHoYo.GenshinImpact.Proxy")) {
            // Continue ???
        } else {
            return
        }
        
       
        EzXHelperInit.initHandleLoadPackage(lpparam)

        findMethod(Activity::class.java, true) { name == "onCreate" }.hookBefore { param ->
            activity = param.thisObject as Activity
        }
        findMethod("com.miHoYo.GetMobileInfo.MainActivity") { name == "onCreate" }.hookBefore { param ->
            activity = param.thisObject as Activity
            showDialog()
        }
    }

    private fun httpUtils(url: String, mode: String = "GET", data: String = "", callback: (HttpURLConnection, String) -> Unit) {
        var ret: String
        URL("$server$url").apply {
            val conn = if (server.startsWith("https")) {
                (openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = getDefaultSSLSocketFactory()
                    hostnameVerifier = getDefaultHostnameVerifier()
                }
            } else {
                openConnection() as HttpURLConnection
            }.apply {
                requestMethod = mode
                readTimeout = 8000
                connectTimeout = 8000
                if (mode == "POST") {
                    doOutput = true
                    doInput = true
                    useCaches = false
                    outputStream.apply {
                        write(data.toByteArray())
                        flush()
                    }
                    val input = inputStream
                    val message = ByteArrayOutputStream()

                    var len: Int
                    val buffer = ByteArray(1024)
                    while (input.read(buffer).also { len = it } != -1) {
                        message.write(buffer, 0, len)
                    }
                    input.close()
                    message.close()
                    ret = String(message.toByteArray())
                } else {
                    val response = StringBuilder()
                    var line = ""
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    while (reader.readLine()?.also { line = it } != null) {
                        response.append(line)
                    }
                    ret = response.toString()
                }
            }
            callback(conn, ret)
        }
    }

    private fun showDialog() {
        AlertDialog.Builder(activity).apply {
            setCancelable(false)
            setTitle("Добро пожаловать в Genshin Impact Offline")
            setMessage("Click on Yuuki Server for join Server Yuuki\nClick on Localhost if you want play on Localhost\n\nThis module was modified by MrModer (discord: mrmoder)\nWith love from Russia 🇷🇺")


            // TODO: add patch metadata
            // TODO: remove patch metadata

            // Yuuki
            
            setNegativeButton("Custom Server (HTTP/HTTPS)") { _, _ ->
                CustomServer()
            }
            setPositiveButton("Yuuki Server") { _, _ ->
                showYuukiServer()
            }
            setNeutralButton("Localhost (HTTP)") { _, _ ->
                LocalHost()
                //activity.finish() // use this to close?                
            }

        }.show()
    }
    
    private fun LocalHost() {
        AlertDialog.Builder(activity).apply {
            setCancelable(false)
            setTitle("Добро пожаловать в Genshin Impact Offline")
            setMessage("Enter Port\nleave blank for using default port (54321)")
            setView(ScrollView(context).apply {

            addView(EditText(activity).apply {
                val str = ""
                setText(str.toCharArray(), 0, str.length)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) {}
                    override fun onTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) {}
                    @SuppressLint("CommitPrefEdits")
                    override fun afterTextChanged(p0: Editable) {
                        server = p0.toString()
                    }
                })
            })
            
            })
            // Yuuki
            setPositiveButton("Enter Localhost") { _, _ ->
                if (server == "") {
                    server = "http://127.0.0.1:54321"
                    hook()
                    Toast.makeText(activity, "Entering Localhost with port 54321", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, "Entering Localhost with port ${server}", Toast.LENGTH_LONG).show()
                    server = "http://127.0.0.1:${server}"
                    hook()
                }
            }
            setNeutralButton("Back") { _, _ ->
                showDialog()
            }

        }.show()
    }
    
    private fun CustomServer() {
        AlertDialog.Builder(activity).apply {
            setCancelable(false)
            setTitle("Добро пожаловать в Genshin Impact Offline")
            setMessage("Enter address/domain WITH https:// or http://\nExample : http://8.8.8.8\nDo not enter the port!\nDO NOT ENTER!")
            setView(ScrollView(context).apply {

            addView(EditText(activity).apply {
                val str = ""
                setText(str.toCharArray(), 0, str.length)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) {}
                    override fun onTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) {}
                    @SuppressLint("CommitPrefEdits")
                    override fun afterTextChanged(p0: Editable) {
                        server = p0.toString()
                    }
                })
            })
            
            })
            
            setNegativeButton("Continue") { _, _ ->
                if (server == "") {
                    hook()
                    Toast.makeText(activity, "Entering Official Server", Toast.LENGTH_LONG).show()
                } else {
                    CustomServer_Port()
                }
            }
            setNeutralButton("Back") { _, _ ->
                showDialog()
                //activity.finish() // use this to close?                
            }
            setPositiveButton("💖") { _, _ ->
                showNekoAbra()
                hook()
                Toast.makeText(activity, "Добро пожаловать на NekoAbraGIO!💖", Toast.LENGTH_LONG).show()
            }

        }.show()

    }
    
    private fun CustomServer_Port() {
        AlertDialog.Builder(activity).apply {
            setCancelable(false)
            setTitle("Добро пожаловать в Genshin Impact Offline")
            setMessage("Set Port\nLeave blank for using default Port")
            setView(ScrollView(context).apply {

            addView(EditText(activity).apply {
                val str = ""
                portSet = ""
                setText(str.toCharArray(), 0, str.length)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) {}
                    override fun onTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) {}
                    @SuppressLint("CommitPrefEdits")
                    override fun afterTextChanged(p0: Editable) {
                        portSet = p0.toString()
                    }
                })
            })
            
            })
            
            setPositiveButton("Enter Custom Server") { _, _ ->
                if (portSet != "") {
                    Toast.makeText(activity, "Entering ${server} with Port ${portSet}", Toast.LENGTH_LONG).show()
                    server = "${server}:${portSet}"
                    hook()
                } else {
                    Toast.makeText(activity, "Entering ${server} with Default Port", Toast.LENGTH_LONG).show()
                    server = "${server}"
                    hook()
                }
            }
            setNeutralButton("Back") { _, _ ->
                showDialog()
                //activity.finish() // use this to close?                
            }
            

        }.show()
    }
    
    private fun showYuukiServer() {
        server = "https://ps.yuuki.me"
        hook()
    }

    private fun showNekoAbra() {
        server = "http://10.242.1.1:21000"
        hook()
    }

    inner class MoveOnTouchListener : View.OnTouchListener {
        private var originalXPos = 0
        private var originalYPos = 0

        private var offsetX = 0f
        private var offsetY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.rawX
                    val y = event.rawY

                    val location = IntArray(2)
                    v.getLocationOnScreen(location)

                    originalXPos = location[0]
                    originalYPos = location[1]

                    offsetX = x - originalXPos
                    offsetY = y - originalYPos
                }
                MotionEvent.ACTION_MOVE -> {
                    val onScreen = IntArray(2)
                    v.getLocationOnScreen(onScreen)

                    val x = event.rawX
                    val y = event.rawY

                    val params: WindowManager.LayoutParams = v.layoutParams as WindowManager.LayoutParams

                    val newX = (x - offsetX).toInt()
                    val newY = (y - offsetY).toInt()

                    if (newX == originalXPos && newY == originalYPos) {
                        return false
                    }

                    params.x = newX
                    params.y = newY

                    windowManager.updateViewLayout(v, params)
                }
            }
            return false
        }
    }

    private fun sslHook() {
        // OkHttp3 Hook
        findMethodOrNull("com.combosdk.lib.third.okhttp3.OkHttpClient\$Builder") { name == "build" }?.hookBefore {
            it.thisObject.invokeMethod("sslSocketFactory", args(getDefaultSSLSocketFactory()), argTypes(SSLSocketFactory::class.java))
            it.thisObject.invokeMethod("hostnameVerifier", args(getDefaultHostnameVerifier()), argTypes(HostnameVerifier::class.java))
        }
        findMethodOrNull("okhttp3.OkHttpClient\$Builder") { name == "build" }?.hookBefore {
            it.thisObject.invokeMethod("sslSocketFactory", args(getDefaultSSLSocketFactory(), DefaultTrustManager()), argTypes(SSLSocketFactory::class.java, X509TrustManager::class.java))
            it.thisObject.invokeMethod("hostnameVerifier", args(getDefaultHostnameVerifier()), argTypes(HostnameVerifier::class.java))
        }
        // WebView Hook
        arrayListOf(
            "android.webkit.WebViewClient",
            "cn.sharesdk.framework.g",
            "com.facebook.internal.WebDialog\$DialogWebViewClient",
            "com.geetest.sdk.dialog.views.GtWebView\$c",
            "com.miHoYo.sdk.webview.common.view.ContentWebView\$6"
        ).forEach {
            findMethodOrNull(it) { name == "onReceivedSslError" && parameterTypes[1] == SslErrorHandler::class.java }?.hookBefore { param ->
                (param.args[1] as SslErrorHandler).proceed()
            }
        }
        // Android HttpsURLConnection Hook
        findMethodOrNull("javax.net.ssl.HttpsURLConnection") { name == "getDefaultSSLSocketFactory" }?.hookBefore {
            it.result = getDefaultSSLSocketFactory()
        }
        findMethodOrNull("javax.net.ssl.HttpsURLConnection") { name == "setSSLSocketFactory" }?.hookBefore {
            it.result = null
        }
        findMethodOrNull("javax.net.ssl.HttpsURLConnection") { name == "setDefaultSSLSocketFactory" }?.hookBefore {
            it.result = null
        }
        findMethodOrNull("javax.net.ssl.HttpsURLConnection") { name == "setHostnameVerifier" }?.hookBefore {
            it.result = null
        }
        findMethodOrNull("javax.net.ssl.HttpsURLConnection") { name == "setDefaultHostnameVerifier" }?.hookBefore {
            it.result = null
        }
        findMethodOrNull("javax.net.ssl.HttpsURLConnection") { name == "getDefaultHostnameVerifier" }?.hookBefore {
            it.result = getDefaultHostnameVerifier()
        }
    }

    private fun hook() {
        findMethod("com.miHoYo.sdk.webview.MiHoYoWebview") { name == "load" && parameterTypes[0] == String::class.java && parameterTypes[1] == String::class.java }.hookBefore {
            replaceUrl(it, 1)
        }
        findAllMethods("android.webkit.WebView") { name == "loadUrl" }.hookBefore {
            replaceUrl(it, 0)
        }
        findAllMethods("android.webkit.WebView") { name == "postUrl" }.hookBefore {
            replaceUrl(it, 0)
        }

        findMethod("okhttp3.HttpUrl") { name == "parse" && parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
        findMethod("com.combosdk.lib.third.okhttp3.HttpUrl") { name == "parse" && parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }

        findMethod("com.google.gson.Gson") { name == "fromJson" && parameterTypes[0] == String::class.java && parameterTypes[1] == java.lang.reflect.Type::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
        findConstructor("java.net.URL") { parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
        findMethod("com.combosdk.lib.third.okhttp3.Request\$Builder") { name == "url" && parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
        findMethod("okhttp3.Request\$Builder") { name == "url" && parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
    }

    private fun replaceUrl(method: XC_MethodHook.MethodHookParam, args: Int) {
        
        if (server == "") return
        if (method.args[args].toString() == "") return

        //XposedBridge.log("old: " + method.args[args].toString())
        if (method.args[args].toString().startsWith("[{\"area\":")) return

        val m = regex.matcher(method.args[args].toString())
        if (m.find()) {
         method.args[args] = m.replaceAll(server)
        }
        //XposedBridge.log("new: " + method.args[args].toString())
    }
}
