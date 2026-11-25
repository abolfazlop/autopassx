package dev.autopassx

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autopassx.security.EncryptedVault
import org.json.JSONObject

private val Ink = Color(0xFF10121B)
private val Panel = Color(0xFF1B1E2B)
private val Violet = Color(0xFF9B8CFF)
private val Muted = Color(0xFF9BA0B4)

class MainActivity : ComponentActivity() {
    private var autofillEnabled by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshAutofillState()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Violet, secondary = Color(0xFF72DCC4),
                    background = Ink, surface = Panel, onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                AutoPassApp(
                    enabled = autofillEnabled,
                    records = remember { mutableStateListOf<JSONObject>() },
                    onEnable = { requestAutofill() },
                    onRefresh = { refreshAutofillState() },
                    onSave = { label, pkg, username, password ->
                        val record = JSONObject().put("label", label).put("package", pkg)
                            .put("username", username).put("password", password)
                        EncryptedVault(this).put("app:$pkg:$username", record)
                    },
                    onDelete = { id -> EncryptedVault(this).delete(id) },
                    onCopy = { value ->
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("AutoPass X", value))
                    },
                    onLoad = { target ->
                        target.clear()
                        target.addAll(EncryptedVault(this).all())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAutofillState()
    }

    private fun refreshAutofillState() {
        val selected = Settings.Secure.getString(contentResolver, "autofill_service")
        val component = ComponentName.unflattenFromString(selected ?: "")
        autofillEnabled = component?.packageName == packageName
    }

    private fun requestAutofill() {
        refreshAutofillState()
        if (autofillEnabled) return
        startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = android.net.Uri.parse("package:$packageName")
        })
    }
}

@Composable
private fun AutoPassApp(
    enabled: Boolean,
    records: MutableList<JSONObject>,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onCopy: (String) -> Unit,
    onLoad: (MutableList<JSONObject>) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var revealIds by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { onLoad(records) }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(
                    Brush.linearGradient(listOf(Violet, Color(0xFF5B51B8)))
                ), contentAlignment = Alignment.Center) {
                    Text("A", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Ink)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("AUTOPASS", fontSize = 11.sp, color = Muted, letterSpacing = 3.sp)
                    Text("X", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                Text("v0.2", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(26.dp))
            AnimatedContent(tab, transitionSpec = {
                (fadeIn() + slideInVertically { it / 8 }) togetherWith
                    (fadeOut() + slideOutVertically { -it / 8 })
            }, label = "page") { page ->
                Column(Modifier.weight(1f)) {
                    when (page) {
                        0 -> {
                            Text("رمزهای عبور تو", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text("همه‌چیز رمزگذاری‌شده و روی همین دستگاه", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(18.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(query, { query = it }, Modifier.weight(1f),
                                    placeholder = { Text("جست‌وجوی حساب...") }, singleLine = true,
                                    shape = RoundedCornerShape(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { showAdd = true }, shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 17.dp)) {
                                    Text("+")
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            if (records.isEmpty()) {
                                EmptyVault { showAdd = true }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(records.filter {
                                        val hay = it.optString("label") + it.optString("package") + it.optString("username")
                                        hay.contains(query, true)
                                    }, key = { it.optString("package") + it.optString("username") }) { item ->
                                        val id = "app:${item.optString("package")}:${item.optString("username")}"
                                        AccountCard(item, revealIds.contains(id),
                                            onReveal = { revealIds = if (revealIds.contains(id)) revealIds - id else revealIds + id },
                                            onCopyUser = { onCopy(item.optString("username")) },
                                            onCopyPass = { onCopy(item.optString("password")) },
                                            onDelete = { onDelete(id); records.remove(item) })
                                    }
                                }
                            }
                        }
                        1 -> {
                            Text("وضعیت سرویس", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.padding(18.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (enabled) Color(0xFF54D6A2) else Color(0xFFFFB45C)))
                                        Spacer(Modifier.width(10.dp))
                                        Text(if (enabled) "Autofill فعال است" else "Autofill هنوز انتخاب نشده", fontWeight = FontWeight.SemiBold)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(if (enabled) "AutoPass X به‌عنوان سرویس تکمیل خودکار انتخاب شده. برای بررسی دوباره وضعیت، دکمه زیر را بزن." else "فقط یک‌بار AutoPass X را در تنظیمات Android به‌عنوان سرویس تکمیل خودکار انتخاب کن. برنامه وضعیت واقعی سیستم را می‌خواند و پس از فعال‌شدن دیگر درخواست را تکرار نمی‌کند.", color = Muted, fontSize = 13.sp)
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = if (enabled) onRefresh else onEnable, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (enabled) "بررسی وضعیت" else "فعال‌سازی Autofill")
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.padding(18.dp)) {
                                    Text("LSPosed / LibXposed API 102", fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp))
                                    Text("ماژول مستقل در همین پروژه اضافه شده است. برای فعال‌سازی، در LSPosed آن را روشن و فقط برنامه‌هایی را در Scope انتخاب کن که واقعاً می‌خواهی با آن‌ها کار کند. فعلاً این ماژول فقط چرخه‌ی بارگذاری و وضعیت اجرا را ثبت می‌کند؛ هیچ هوک جمع‌آوری رمز نصب نمی‌کند.", color = Muted, fontSize = 13.sp)
                                }
                            }
                        }
                        else -> {
                            Text("امنیت و تنظیمات", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            InfoTile("ذخیره‌سازی", "AES-256-GCM با کلید Android Keystore")
                            InfoTile("پشتیبان‌گیری", "غیرفعال؛ داده‌ها از بکاپ ابری خارج‌اند")
                            InfoTile("حریم خصوصی", "بدون سرور، تحلیل‌گر یا ثبت کلیدهای فشرده‌شده")
                            InfoTile("ذخیره خودکار", "از مسیر استاندارد Save در Android Autofill؛ نمایش پیشنهاد ذخیره وابسته به خود برنامه مقصد است.")
                        }
                    }
                }
            }
            NavigationBar(containerColor = Panel, contentColor = Violet) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("▤") }, label = { Text("صندوق") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("◉") }, label = { Text("اتصال") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text("امنیت") })
            }
        }
    }
    if (showAdd) AddAccountDialog(onDismiss = { showAdd = false }, onSave = { label, pkg, user, pass ->
        onSave(label, pkg, user, pass)
        onLoad(records)
        showAdd = false
    })
}

@Composable
private fun EmptyVault(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 55.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("◇", fontSize = 54.sp, color = Violet)
        Spacer(Modifier.height(10.dp))
        Text("صندوق رمزها خالی است", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("اولین حساب خودت را اضافه کن", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAdd) { Text("افزودن حساب") }
    }
}

@Composable
private fun AccountCard(item: JSONObject, revealed: Boolean, onReveal: () -> Unit,
                        onCopyUser: () -> Unit, onCopyPass: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF302B50)), contentAlignment = Alignment.Center) {
                    Text(item.optString("label").take(1).uppercase(), color = Violet, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.optString("label", "حساب"), fontWeight = FontWeight.Bold)
                    Text(item.optString("package"), color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = onDelete) { Text("حذف", color = Color(0xFFFF8585)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("نام کاربری", color = Muted, fontSize = 11.sp)
                    Text(item.optString("username"), fontSize = 14.sp)
                }
                TextButton(onClick = onCopyUser) { Text("کپی") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("رمز عبور", color = Muted, fontSize = 11.sp)
                    Text(if (revealed) item.optString("password") else "••••••••••", fontSize = 14.sp)
                }
                TextButton(onClick = onReveal) { Text(if (revealed) "مخفی" else "نمایش") }
                TextButton(onClick = onCopyPass) { Text("کپی") }
            }
        }
    }
}

@Composable
private fun InfoTile(title: String, body: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("افزودن حساب") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("نام سرویس") }, singleLine = true)
                OutlinedTextField(pkg, { pkg = it }, label = { Text("Package name") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("نام کاربری / ایمیل") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("رمز عبور") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "مخفی" else "نمایش") } })
            }
        },
        confirmButton = { Button(onClick = { if (pkg.isNotBlank() && username.isNotBlank() && password.isNotBlank()) onSave(label.ifBlank { pkg }, pkg.trim(), username.trim(), password) }) { Text("ذخیره امن") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } })
}