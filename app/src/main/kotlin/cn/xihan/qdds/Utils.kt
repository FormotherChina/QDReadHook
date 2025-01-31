package cn.xihan.qdds

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.highcapable.yukihookapi.hook.log.loggerE
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess


/**
 * @项目名 : QDReadHook
 * @作者 : MissYang
 * @创建时间 : 2022/8/28 16:13
 * @介绍 :
 */
/**
 * 通过反射获取控件
 * @param name 字段名
 */
@Throws(NoSuchFieldException::class, IllegalAccessException::class)
inline fun <reified T : View> Any.getView(name: String): T? = getParam<T>(name)

/**
 * 反射获取任何类型
 */
@Throws(NoSuchFieldException::class, IllegalAccessException::class)
inline fun <reified T> Any.getParam(name: String): T? = javaClass.getDeclaredField(name).apply {
    isAccessible = true
}[this] as? T

/**
 * Xposed 设置字段值
 */
fun Any.setParam(name: String, value: Any) {
    when (value) {
        is Int -> XposedHelpers.setIntField(this, name, value)
        is Boolean -> XposedHelpers.setBooleanField(this, name, value)
        is String -> XposedHelpers.setObjectField(this, name, value)
        is Long -> XposedHelpers.setLongField(this, name, value)
        is Float -> XposedHelpers.setFloatField(this, name, value)
        is Double -> XposedHelpers.setDoubleField(this, name, value)
        is Short -> XposedHelpers.setShortField(this, name, value)
        is Byte -> XposedHelpers.setByteField(this, name, value)
        is Char -> XposedHelpers.setCharField(this, name, value)
        else -> XposedHelpers.setObjectField(this, name, value)
    }
}

/**
 * 批量 setParam
 */
fun Any.setParams(vararg params: Pair<String, Any>) {
    params.forEach {
        setParam(it.first, it.second)
    }
}

/**
 * 利用 Reflection 获取当前的系统 Context
 */
fun getSystemContext(): Context {
    val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
    val activityThread =
        XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
    val context = XposedHelpers.callMethod(activityThread, "getSystemContext") as? Context
    return context ?: throw Error("Failed to get system context.")
}

/**
 * 获取指定应用的 APK 路径
 */
fun Context.getApplicationApkPath(packageName: String): String {
    val pm = this.packageManager
    val apkPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(0)
        ).publicSourceDir
    } else {
        pm.getApplicationInfo(packageName, 0).publicSourceDir
    }
    return apkPath ?: throw Error("Failed to get the APK path of $packageName")
}

/**
 * 重启当前应用
 */
fun Activity.restartApplication() {
    // https://stackoverflow.com/a/58530756
    val pm = packageManager
    val intent = pm.getLaunchIntentForPackage(packageName)
    finishAffinity()
    startActivity(intent)
    exitProcess(0)
}

/**
 * 获取指定应用的版本号
 */
fun Context.getVersionCode(packageName: String): Int {
    val pm = packageManager
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            ).longVersionCode.toInt()
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            pm.getPackageInfo(packageName, 0).longVersionCode.toInt()
        }

        else -> {
            pm.getPackageInfo(packageName, 0).versionCode
        }
    }

}

/**
 * 打印当前调用栈
 */
fun printCallStack(className: String = "") {
    val stringBuilder = StringBuilder()
    stringBuilder.appendLine("className: $className")
    stringBuilder.appendLine("Dump Stack: ---------------start----------------")
    val ex = Throwable()
    val stackElements = ex.stackTrace
    stackElements.forEachIndexed { index, stackTraceElement ->
        stringBuilder.appendLine("Dump Stack: $index: $stackTraceElement")
    }
    stringBuilder.appendLine("Dump Stack: ---------------end----------------")
    loggerE(msg = stringBuilder.toString())
}

fun Any.printCallStack() {
    printCallStack(this.javaClass.name)
}

/**
 * 容错安全运行方法
 */
inline fun safeRun(block: () -> Unit) = try {
    block()
} catch (e: Throwable) {
//    if (BuildConfig.DEBUG) {
//        loggerE(msg = "safeRun 报错: ${e.message}", e = e)
//    }
} catch (_: Exception) {

}

fun String.writeTextFile(fileName: String = "test") {
    var index = 0
    while (File(
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/QDReader",
            "$fileName-$index.txt"
        ).exists()
    ) {
        index++
    }
    File(
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/QDReader",
        "$fileName-$index.txt"
    ).apply {
        parentFile?.mkdirs()
        if (!exists()) {
            createNewFile()
        }
        writeText(this@writeTextFile)
    }
}

/**
 * dp 转 px
 */
fun Context.dp2px(dp: Float): Int {
    val scale = resources.displayMetrics.density
    return (dp * scale + 0.5f).toInt()
}

/**
 * 检查模块更新
 */
@Throws(Exception::class)
fun Context.checkModuleUpdate() {
    // 创建一个子线程
    Thread {
        Looper.prepare()
        // Java 原生网络请求
        val url = URL("https://api.github.com/repos/xihan123/QDReadHook/releases/latest")
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.114 Safari/537.36"
            )
        }
        try {
            connection.connect()
            if (connection.responseCode == 200) {
                val inputStream = connection.inputStream
                val bufferedReader = BufferedReader(InputStreamReader(inputStream))
                val stringBuilder = StringBuilder()
                bufferedReader.forEachLine {
                    stringBuilder.append(it)
                }
                val jsonObject = JSONObject(stringBuilder.toString())
                val versionName = jsonObject.getString("tag_name")
                val downloadUrl = jsonObject.getJSONArray("assets").getJSONObject(0)
                    .getString("browser_download_url")
                val releaseNote = jsonObject.getString("body")
                if (versionName != BuildConfig.VERSION_NAME) {
                    alertDialog {
                        title = "发现新版本: $versionName"
                        message = "更新内容:\n$releaseNote"
                        positiveButton("下载更新") {
                            startActivity(Intent(Intent.ACTION_VIEW).also {
                                it.data = Uri.parse(downloadUrl)
                            })
                        }
                        negativeButton("返回") {
                            it.dismiss()
                        }
                        build()
                        show()
                    }
                } else {
                    Toast.makeText(this, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "检查更新失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            loggerE(msg = "checkModuleUpdate 报错: ${e.message}")
        } finally {
            connection.disconnect()
        }
        Looper.loop()
    }.start()
}

/**
 * 容错的根据正则修改字符串返回字符串
 * @param enableRegex 启用正则表达式
 * @param regex 正则表达式
 * @param replacement 替换内容
 */
fun String.safeReplace(
    enableRegex: Boolean = false,
    regex: String = "",
    replacement: String = "",
): String {
    return try {
        if (enableRegex) {
            this.replace(Regex(regex), replacement)
        } else {
            this.replace(regex, replacement)
        }
    } catch (e: Exception) {
        loggerE(msg = "safeReplace 报错: ${e.message}")
        this
    }
}

/**
 * 根据 ReplaceRuleOption 中 replaceRuleList 修改返回字符串
 * @param replaceRuleList 替换规则列表
 */
fun String.replaceByReplaceRuleList(replaceRuleList: List<OptionEntity.ReplaceRuleOption.ReplaceItem>): String =
    try {
        var result = this
        replaceRuleList.forEach {
            result =
                result.safeReplace(it.enableRegularExpressions, it.replaceRuleRegex, it.replaceWith)
        }
        result
    } catch (e: Exception) {
        loggerE(msg = "replaceByReplaceRuleList 报错: ${e.message}")
        this
    }

/**
 *  判断输入的 十六进制颜色代码
 */
fun String.isHexColor(): Boolean {
    return try {
        Color.parseColor(this)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * 字符串复制到剪切板
 */
fun Context.copyToClipboard(text: String) {
    val clipboardManager =
        this.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText(null, text))
}

/*
 *
 * 发起添加群流程。群号：模块交流群(727983520)
 * 调用 joinQQGroup 即可发起手Q客户端申请加群 模块交流群(727983520)
 *
 * @return 返回true表示呼起手Q成功，返回false表示呼起失败
 */
fun Context.joinQQGroup(key: String): Boolean {
    val intent = Intent()
    intent.data =
        Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key")
    return try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        // 未安装手Q或安装的版本不支持
        Toast.makeText(this, "未安装手Q或安装的版本不支持", Toast.LENGTH_SHORT).show()
        false
    }
}


/**
 * 隐藏应用图标
 */
fun Context.hideAppIcon() {
    val componentName = ComponentName(this, MainActivity::class.java.name)
    if (packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}

/**
 * 显示应用图标
 */
fun Context.showAppIcon() {
    val componentName = ComponentName(this, MainActivity::class.java.name)
    if (packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}

/**
 * 请求权限
 */
fun Context.requestPermission(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
) {
    if (this.applicationInfo.targetSdkVersion > 30) {
        XXPermissions.with(this)
            .permission(Permission.MANAGE_EXTERNAL_STORAGE, Permission.REQUEST_INSTALL_PACKAGES)
            .request { _, allGranted ->
                if (allGranted) {
                    onGranted()
                } else {
                    onDenied()
                }
            }
    } else {
        XXPermissions.with(this)
            .permission(Permission.Group.STORAGE.plus(Permission.REQUEST_INSTALL_PACKAGES))
            .request { _, allGranted ->
                if (allGranted) {
                    onGranted()
                } else {
                    onDenied()
                }
            }
    }
}

/**
 * 跳转获取权限
 */
fun Context.jumpToPermission() {
    if (this.applicationInfo.targetSdkVersion > 30) {
        XXPermissions.startPermissionActivity(this, Permission.MANAGE_EXTERNAL_STORAGE)
    } else {
        XXPermissions.startPermissionActivity(this, Permission.Group.STORAGE)
    }
}

/**
 * 判断字符串是否为数字
 */
fun String.isNumber(): Boolean =
    try {
        this.toDouble()
        true
    } catch (e: Exception) {
        false
    }

/**
 * 默认浏览器打开url
 */
fun Context.openUrl(url: String) = safeRun {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.data = Uri.parse(url)
    startActivity(intent)
}

/**
 * toast
 * @param msg String
 */
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/**
 * 打印不支持的版本号
 */
fun String.printlnNotSupportVersion(versionCode: Int = 0) =
    loggerE(msg = "${this}不支持的版本号为: $versionCode")

/**
 * 更新列表选项实体
 */
fun MutableList<OptionEntity.SelectedModel>.updateSelectedListOptionEntity(newConfigurations: List<OptionEntity.SelectedModel>): MutableList<OptionEntity.SelectedModel> {
    // 添加新配置
    newConfigurations.forEach { newConfig ->
        if (!any { it.title == newConfig.title }) {
            plusAssign(newConfig)
        }
    }
    return this
}

/**
 * 更新列表选项实体
 */
fun MutableList<String>.updateStringListOptionEntity(newConfigurations: List<String>): List<String> {
    // 添加新配置
    newConfigurations.forEach { newConfig ->
        if (!any { it == newConfig }) {
            plusAssign(newConfig)
        }
    }
    return this
}

fun String.loge() {
    loggerE(msg = this)
}

/**
 * 查找或添加列表中的数据
 * @param title 标题
 * @param iterator 迭代器
 */
fun MutableList<OptionEntity.SelectedModel>.findOrPlus(
    title: String,
    iterator: MutableIterator<Any?>,
) = safeRun {
    find { it.title == title }?.let { config ->
        if (config.selected) {
            iterator.remove()
        }
    } ?: plusAssign(
        OptionEntity.SelectedModel(
            title = title
        )
    )
    updateOptionEntity()
}

/**
 * 查找或添加列表中的数据
 * @param title 标题
 */
fun MutableList<OptionEntity.SelectedModel>.findOrPlus(
    title: String,
    actionUnit: () -> Unit = {},
) = safeRun {
    find { it.title == title }?.let { config ->
        if (config.selected) {
            actionUnit()
        }
    } ?: plusAssign(
        OptionEntity.SelectedModel(
            title = title
        )
    )
    updateOptionEntity()
}

/**
 * 多选项对话框
 * @param list 列表
 */
fun Context.multiChoiceSelector(
    list: List<OptionEntity.SelectedModel>,
) = safeRun {
    if (list.isEmpty()) {
        toast("没有可用的选项")
        return
    }
    val checkedItems = BooleanArray(list.size)
    list.forEachIndexed { index, selectedModel ->
        if (selectedModel.selected) {
            checkedItems[index] = true
        }
    }
    multiChoiceSelector(
        list.map { it.title }, checkedItems, "选项列表"
    ) { _, i, isChecked ->
        checkedItems[i] = isChecked
    }.doOnDismiss {
        checkedItems.forEachIndexed { index, b ->
            list[index].selected = b
        }
        updateOptionEntity()
    }
}

typealias M = Modifier

@Composable
fun <T> rememberMutableStateOf(value: T): MutableState<T> = remember { mutableStateOf(value) }



