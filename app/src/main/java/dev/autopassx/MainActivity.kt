package dev.autopassx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autopassx.security.EncryptedVault
import dev.autopassx.security.PasswordGenerator
import org.json.JSONObject

private val Bg = Color(0xFF0C0E16)
private val SurfaceDark = Color(0xFF171B29)
private val SurfaceRaised = Color(0xFF20263A)
private val Accent = Color(0xFFA99BFF)
private val TextMuted = Color(0xFFA5AAC0)
private val Good = Color(0xFF58D6A5)

class MainActivity : ComponentActivity() {
    private var autofillEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshAutofillState()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, secondary = Good, background = Bg, surface = SurfaceDark,
                onBackground = Color.White, onSurface = Color.White, error = Color(0xFFFF8585)
            )) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val records = remember { mutableStateListOf<JSONObject>() }
                    AutoPassRoot(
                        enabled = autofillEnabled,
                        records = records,
                        onEnable = ::requestAutofill,
                        onRefresh = ::refreshAutofillState,
                        onLoad = { target -> target.clear(); target.addAll(EncryptedVault(this).all()) },
                        onSave = { title, pkg, domain, username, password ->
                            val cleanDomain = normalizeWebsiteDomain(domain)
                            val recordId = if (cleanDomain.isNotBlank()) {
                                "web:$cleanDomain:$username"
                            } else {
                                "app:$pkg:$username"
                            }
                            EncryptedVault(this).put(
                                recordId,
                                JSONObject().put("label", title).put("package", pkg)
                                    .put("domain", cleanDomain).put("username", username).put("password", password)
                            )
                        },
                        onDelete = { id -> EncryptedVault(this).delete(id) },
                        onCopy = { value ->
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("AutoPass X", value))
                        }
                    )
                }
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
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}

@Composable
private fun AutoPassRoot(
    enabled: Boolean,
    records: MutableList<JSONObject>,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    onLoad: (MutableList<JSONObject>) -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(setOf<String>()) }
    var generated by remember { mutableStateOf("") }
    var length by remember { mutableFloatStateOf(20f) }

    LaunchedEffect(Unit) { onLoad(records) }

    Scaffold(
        containerColor = Bg,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark, tonalElevation = 0.dp) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Text("▤", fontSize = 20.sp) }, label = { Text("صندوق") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = { Text("◉", fontSize = 20.sp) }, label = { Text("اتصال") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    icon = { Text("⚄", fontSize = 20.sp) }, label = { Text("تولید رمز") })
                NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 },
                    icon = { Text("⚙", fontSize = 20.sp) }, label = { Text("تنظیمات") })
            }
        }
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets)
                .padding(horizontal = 18.dp)
        ) {
            Header()
            Spacer(Modifier.height(16.dp))
            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 12 }) togetherWith
                        (fadeOut() + slideOutVertically { -it / 12 })
                },
                label = "main-tabs"
            ) { page ->
                when (page) {
                    0 -> VaultPage(
                        records = records, search = search, onSearch = { search = it },
                        onAdd = { showAdd = true }, reveal = reveal,
                        onReveal = { id -> reveal = if (id in reveal) reveal - id else reveal + id },
                        onCopy = onCopy,
                        onDelete = { id, item -> onDelete(id); records.remove(item) }
                    )
                    1 -> ConnectionPage(enabled, onEnable, onRefresh)
                    2 -> GeneratorPage(length, { length = it }, generated, { generated = it }, onCopy)
                    else -> SettingsPage()
                }
            }
        }
    }

    if (showAdd) {
        AddAccountDialog(
            onDismiss = { showAdd = false },
            onSave = { title, pkg, domain, user, pass ->
                onSave(title, pkg, domain, user, pass)
                onLoad(records)
                showAdd = false
            }
        )
    }
}

@Composable
private fun normalizeWebsiteDomain(raw: String): String {
    return raw.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .substringBefore('/').substringBefore(':').trimEnd('.')
}

@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Accent, Color(0xFF6255C7)))),
            contentAlignment = Alignment.Center
        ) { Text("A", color = Bg, fontSize = 27.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("AUTOPASS X", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
            Text("مدیریت امن رمزهای عبور", color = TextMuted, fontSize = 11.sp)
        }
        Surface(color = SurfaceRaised, shape = CircleShape) {
            Text("نسخه ۰٫۳", Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = Accent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
private fun VaultPage(
    records: List<JSONObject>, search: String, onSearch: (String) -> Unit, onAdd: () -> Unit,
    reveal: Set<String>, onReveal: (String) -> Unit, onCopy: (String) -> Unit,
    onDelete: (String, JSONObject) -> Unit
) {
    val filtered = records.filter {
        (it.optString("label") + " " + it.optString("package") + " " +
            it.optString("domain") + " " + it.optString("username"))
            .contains(search, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("صندوق رمزها", "حساب‌هایت روی دستگاه و به‌صورت رمزگذاری‌شده نگهداری می‌شوند")
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search, onValueChange = onSearch,
                modifier = Modifier.weight(1f),
                placeholder = { Text("جست‌وجوی حساب") }, singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAdd, modifier = Modifier.height(54.dp),
                shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text("+ افزودن")
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} حساب", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("رمزگذاری‌شده با Keystore", color = Good, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("◇", fontSize = 48.sp, color = Accent)
                    Spacer(Modifier.height(8.dp))
                    Text(if (search.isBlank()) "هنوز حسابی اضافه نکردی" else "نتیجه‌ای پیدا نشد",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("برای شروع، یک حساب جدید بساز", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onAdd) { Text("افزودن حساب") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = {
                    it.optString("domain") + ":" + it.optString("package") + ":" + it.optString("username")
                }) { item ->
                    val domain = item.optString("domain")
                    val id = if (domain.isNotBlank()) {
                        "web:$domain:${item.optString("username")}"
                    } else {
                        "app:${item.optString("package")}:${item.optString("username")}"
                    }
                    AccountCard(
                        item, id in reveal, { onReveal(id) },
                        { onCopy(item.optString("username")) },
                        { onCopy(item.optString("password")) },
                        { onDelete(id, item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    item: JSONObject, revealed: Boolean, onReveal: () -> Unit,
    onCopyUser: () -> Unit, onCopyPass: () -> Unit, onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(SurfaceRaised),
                    contentAlignment = Alignment.Center
                ) { Text(item.optString("label").take(1).uppercase(), color = Accent, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.optString("label", "حساب"), color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        item.optString("domain").ifBlank { item.optString("package") },
                        color = TextMuted, fontSize = 10.sp
                    )
                }
                TextButton(onClick = onDelete) { Text("حذف", color = Color(0xFFFF8585)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 9.dp), color = SurfaceRaised)
            CredentialRow("نام کاربری", item.optString("username"), "کپی", onCopyUser)
            Spacer(Modifier.height(7.dp))
            CredentialRow("رمز عبور", if (revealed) item.optString("password") else "••••••••••••", if (revealed) "مخفی" else "نمایش", onReveal)
            TextButton(onClick = onCopyPass, modifier = Modifier.align(Alignment.Start)) { Text("کپی رمز") }
        }
    }
}

@Composable
private fun CredentialRow(title: String, value: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMuted, fontSize = 10.sp)
            Text(value, color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
        TextButton(onClick = onAction) { Text(action, color = Accent) }
    }
}

@Composable
private fun ConnectionPage(enabled: Boolean, onEnable: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageTitle("اتصال و تکمیل خودکار", "وضعیت سرویس‌های AutoPass X را مدیریت کن")
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (enabled) Good else Color(0xFFFFB45C)))
                    Spacer(Modifier.width(9.dp))
                    Text(if (enabled) "Autofill فعال است" else "Autofill غیرفعال است",
                        color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    if (enabled) "این گوشی AutoPass X را به‌عنوان سرویس تکمیل خودکار انتخاب کرده است."
                    else "برای پیشنهاد رمزها هنگام ورود، AutoPass X را در تنظیمات سیستم به‌عنوان سرویس Autofill انتخاب کن.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp
                )
                Spacer(Modifier.height(15.dp))
                Button(onClick = if (enabled) onRefresh else onEnable, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)) {
                    Text(if (enabled) "بررسی دوباره وضعیت" else "فعال‌سازی Autofill")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("ماژول LSPosed", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("ورودی LibXposed API 102 در پروژه قرار دارد. پس از نصب APK، در LSPosed آن را فعال کن. نسخه فعلی فقط بارگذاری ماژول را ثبت می‌کند و هنوز هوک اختصاصی برای هر اپ ندارد.", color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("نکته سازگاری", color = Accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("نمایش پیشنهاد Autofill به پشتیبانی برنامه یا وب‌سایت مقصد از فیلدهای استاندارد Android وابسته است.", color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun GeneratorPage(
    length: Float, onLength: (Float) -> Unit, generated: String,
    onGenerated: (String) -> Unit, onCopy: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageTitle("تولید رمز قوی", "یک رمز تصادفی برای حساب جدید بساز")
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("طول رمز: ${length.toInt()} کاراکتر", color = Color.White, fontWeight = FontWeight.Bold)
                Slider(value = length, onValueChange = onLength, valueRange = 16f..40f, steps = 23)
                Spacer(Modifier.height(10.dp))
                Text(if (generated.isBlank()) "برای ساخت رمز دکمه را بزن" else generated,
                    color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onGenerated(PasswordGenerator.generate(length.toInt())) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("ساخت رمز جدید") }
                OutlinedButton(onClick = { if (generated.isNotBlank()) onCopy(generated) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    enabled = generated.isNotBlank()) { Text("کپی رمز") }
            }
        }
    }
}

@Composable
private fun SettingsPage() {
    Column(Modifier.fillMaxSize()) {
        PageTitle("امنیت و حریم خصوصی", "اطلاعات و محدودیت‌های نسخه فعلی")
        Spacer(Modifier.height(16.dp))
        SettingTile("رمزگذاری صندوق", "AES-GCM با کلید نگهداری‌شده در Android Keystore")
        SettingTile("پشتیبان‌گیری", "بکاپ Android برای داده‌های برنامه غیرفعال است")
        SettingTile("پردازش محلی", "اطلاعات ورود به سرور ارسال نمی‌شود")
        SettingTile("قفل بیومتریک", "هنوز در این نسخه پیاده‌سازی نشده است")
        SettingTile("ذخیره خودکار", "ذخیره از مسیر استاندارد Android Autofill انجام می‌شود و نمایش تأیید به سیستم‌عامل و برنامه مقصد وابسته است")
    }
}

@Composable
private fun SettingTile(title: String, detail: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(detail, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن حساب جدید", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("نام سرویس (مثلاً Google)") }, singleLine = true)
                OutlinedTextField(pkg, { pkg = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("شناسه برنامه (برای اپلیکیشن)") }, singleLine = true,
                    placeholder = { Text("com.example.app") })
                OutlinedTextField(domain, { domain = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("دامنه وب‌سایت (اختیاری)") }, singleLine = true,
                    placeholder = { Text("example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("نام کاربری یا ایمیل") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("رمز عبور") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "مخفی" else "نمایش")
                        }
                    })
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    domain.isBlank() && (pkg.isBlank() || !pkg.contains('.')) -> error = "شناسه برنامه یا دامنه وب‌سایت معتبر وارد کن"
                    domain.isNotBlank() && !normalizeWebsiteDomain(domain).contains('.') -> error = "دامنه وب‌سایت معتبر وارد کن"
                    username.isBlank() -> error = "نام کاربری را وارد کن"
                    password.isBlank() -> error = "رمز عبور را وارد کن"
                    else -> onSave(
                        title.ifBlank { domain.ifBlank { pkg } },
                        pkg.trim(),
                        normalizeWebsiteDomain(domain),
                        username.trim(),
                        password
                    )
                }
            }) { Text("ذخیره امن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}
