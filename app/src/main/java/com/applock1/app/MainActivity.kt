package com.applock1.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.applock1.app.data.PrefsManager
import com.applock1.app.services.AdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppLockTheme { MainScreen() } }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Material You Theme — light, dynamic, clean
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AppLockTheme(content: @Composable () -> Unit) {
    val darkScheme = darkColorScheme(
        primary          = Color(0xFF00E5FF), // Neon Cyan
        onPrimary        = Color.Black,
        primaryContainer = Color(0xFF00B8D4).copy(alpha = 0.2f),
        onPrimaryContainer = Color(0xFF84FFFF),
        secondary        = Color(0xFFB388FF), // Neon Purple
        tertiary         = Color(0xFF1E1E2E), // Glass dark
        background       = Color(0xFF0A0A0F), // Deep black background
        surface          = Color(0xFF14141E), // Slightly lighter for cards
        surfaceVariant   = Color(0xFF1E1E2E),
        onSurface        = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFFB0B0C0),
        outline          = Color(0xFF303040),
        error            = Color(0xFFFF1744),
        errorContainer   = Color(0xFFFF1744).copy(alpha = 0.2f)
    )

    val lightScheme = lightColorScheme(
        primary          = Color(0xFF0091EA), // Vibrant Blue
        onPrimary        = Color.White,
        primaryContainer = Color(0xFF0091EA).copy(alpha = 0.1f),
        onPrimaryContainer = Color(0xFF006064),
        secondary        = Color(0xFF651FFF), // Vibrant Purple
        tertiary         = Color(0xFFE0E5EC), // Glass light
        background       = Color(0xFFF5F7FA), // Soft grayish blue background
        surface          = Color(0xFFFFFFFF), // Pure white cards
        surfaceVariant   = Color(0xFFE4E9F2),
        onSurface        = Color(0xFF1A1A24),
        onSurfaceVariant = Color(0xFF505060),
        outline          = Color(0xFFD0D5E0),
        error            = Color(0xFFD50000),
        errorContainer   = Color(0xFFD50000).copy(alpha = 0.1f)
    )

    val currentTheme = AppLock1App.instance.prefs.appTheme
    val darkTheme = when (currentTheme) {
        PrefsManager.AppTheme.DARK -> true
        PrefsManager.AppTheme.LIGHT -> false
        PrefsManager.AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    MaterialTheme(colorScheme = if (darkTheme) darkScheme else lightScheme, content = content)
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppLock1App.instance.prefs }
    val repo = remember { AppLock1App.instance.repo }
    val cs = MaterialTheme.colorScheme

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var globalOn by remember { mutableStateOf(prefs.isGlobalEnabled) }
    var hideNotif by remember { mutableStateOf(prefs.hideNotificationContent) }
    var relockDelay by remember { mutableStateOf(prefs.relockDelay) }
    var appTheme by remember { mutableStateOf(prefs.appTheme) }
    var a11yOk by remember { mutableStateOf(false) }
    var notifOk by remember { mutableStateOf(false) }
    var overlayOk by remember { mutableStateOf(false) }
    var adminOk by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var allApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    
    // Auth state
    var requireAuth by remember { mutableStateOf(prefs.requireAuthToOpen) }
    var authPassed by remember { mutableStateOf(!prefs.requireAuthToOpen) }
    var authError by remember { mutableStateOf<String?>(null) }

    fun checkPerms() {
        val am = ctx.getSystemService(Activity.ACCESSIBILITY_SERVICE) as AccessibilityManager
        a11yOk = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
        notifOk = try {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            flat.contains(ctx.packageName)
        } catch (_: Exception) { false }
        overlayOk = Settings.canDrawOverlays(ctx)
        
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(ctx, AdminReceiver::class.java)
        adminOk = dpm.isAdminActive(adminComponent)
        
        val pow = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        batteryOk = pow.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    LaunchedEffect(Unit) {
        checkPerms()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                try { (ctx as Activity).requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101) } catch (_: Exception) {}
            }
        }
        
        if (requireAuth) {
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val canAuth = BiometricManager.from(ctx).canAuthenticate(authenticators)
            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                // Failsafe: If no secure lock is set, allow access but disable the requirement
                prefs.requireAuthToOpen = false
                requireAuth = false
                authPassed = true
            } else {
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("AppLock1")
                    .setSubtitle("Autenticación requerida")
                    .setAllowedAuthenticators(authenticators)
                    .build()
                
                val prompt = BiometricPrompt(ctx as androidx.fragment.app.FragmentActivity, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authPassed = true
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        authError = errString.toString()
                    }
                })
                prompt.authenticate(promptInfo)
            }
        }
        
        val locked = withContext(Dispatchers.IO) { repo.getLockedSet() }
        val pm = ctx.packageManager
        allApps = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != ctx.packageName }
                .map { info ->
                    AppItem(
                        pkg = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = pm.getApplicationIcon(info.packageName),
                        locked = locked.contains(info.packageName)
                    )
                }
                .sortedWith(compareByDescending<AppItem> { it.locked }.thenBy { it.label })
        }
        loading = false
    }

    val shown = remember(allApps, query) {
        if (query.isBlank()) allApps else allApps.filter { it.label.contains(query, true) }
    }
    
    if (!authPassed) {
        Box(Modifier.fillMaxSize().background(cs.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Glow effect icon
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(cs.primary.copy(alpha = 0.1f), CircleShape)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔒", fontSize = 42.sp)
                }
                Text("AppLock1 Security", color = cs.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (authError != null) {
                    Text(authError!!, color = cs.error, fontSize = 14.sp)
                    Button(onClick = { 
                        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("AppLock1").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
                        BiometricPrompt(ctx as androidx.fragment.app.FragmentActivity, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { authPassed = true }
                        }).authenticate(promptInfo)
                    }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary)) { Text("Desbloquear") }
                } else {
                    CircularProgressIndicator(color = cs.primary)
                }
            }
        }
        return
    }

    val lockedCount = remember(allApps) { allApps.count { it.locked } }

    Scaffold(
        containerColor = cs.background,
        bottomBar = {
            NavigationBar(containerColor = cs.surface, tonalElevation = 3.dp) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text(if (tab == 0) "🔒" else "🔓", fontSize = 20.sp) },
                    label = { Text("Apps") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1; checkPerms() },
                    icon = { Text("⚙️", fontSize = 20.sp) },
                    label = { Text("Ajustes") }
                )
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // ── Hero Header ──────────────────────────────────────────────────
            item {
                HeroHeader(
                    globalOn = globalOn,
                    lockedCount = lockedCount,
                    onToggle = { globalOn = it; prefs.isGlobalEnabled = it }
                )
            }

            when (tab) {
                0 -> {
                    // Search bar
                    item {
                        SearchBar(
                            query = query,
                            onQuery = { query = it },
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                        )
                    }

                    if (loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(color = cs.primary)
                                    Text("Cargando apps...", color = cs.onSurfaceVariant, fontSize = 14.sp)
                                }
                            }
                        }
                    } else {
                        // Section header
                        item {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Apps instaladas", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                Text("${shown.size} apps", color = cs.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                        items(shown, key = { it.pkg }) { app ->
                            AppRow(app = app, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { repo.toggle(app.pkg) }
                                    allApps = allApps.map { if (it.pkg == app.pkg) it.copy(locked = !it.locked) else it }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    item { ConfigTab(
                        a11yOk = a11yOk, notifOk = notifOk, overlayOk = overlayOk, adminOk = adminOk, batteryOk = batteryOk,
                        hideNotif = hideNotif,
                        relockDelay = relockDelay,
                        appTheme = appTheme,
                        requireAuthToOpen = requireAuth,
                        onHide = { hideNotif = it; prefs.hideNotificationContent = it },
                        onRelockDelay = { relockDelay = it; prefs.relockDelay = it },
                        onAppTheme = { 
                            appTheme = it
                            prefs.appTheme = it 
                            val intent = Intent(ctx, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                            ctx.startActivity(intent)
                        },
                        onRequireAuth = { requireAuth = it; prefs.requireAuthToOpen = it },
                        onA11y = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onNotif = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        onOverlay = { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))) },
                        onUsage = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                        onAdmin = { 
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, AdminReceiver::class.java))
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Necesario para evitar que desinstalen la app y evadan el bloqueo.")
                            }
                            ctx.startActivity(intent)
                        },
                        onBattery = {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            ctx.startActivity(intent)
                        }
                    )}
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero Header — Material You inspired large card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun HeroHeader(globalOn: Boolean, lockedCount: Int, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(
        targetValue = if (globalOn) cs.primaryContainer else cs.surfaceVariant,
        animationSpec = tween(400), label = "heroBg"
    )
    val textColor = if (globalOn) cs.onPrimaryContainer else cs.onSurfaceVariant

    Box(
        Modifier.fillMaxWidth().padding(16.dp).padding(top = 4.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(bgColor)
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "AppLock1",
                        color = textColor,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (globalOn) "$lockedCount apps protegidas" else "Protección pausada",
                        color = textColor.copy(alpha = 0.75f),
                        fontSize = 14.sp
                    )
                }
                // Big status icon
                Box(
                    Modifier.size(56.dp).clip(CircleShape)
                        .background(if (globalOn) cs.primary else cs.outline.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (globalOn) "🔒" else "🔓", fontSize = 24.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            // Toggle switch row
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(cs.surface.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (globalOn) "Protección activa" else "Toca para activar",
                    color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = globalOn, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = cs.primary,
                        checkedThumbColor = cs.onPrimary
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Search Bar
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(
        value = query, onValueChange = onQuery,
        placeholder = { Text("Buscar aplicación...", color = cs.onSurfaceVariant) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true, shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.primary,
            unfocusedBorderColor = cs.outline.copy(alpha = 0.5f),
            focusedContainerColor = cs.surfaceVariant.copy(alpha = 0.4f),
            unfocusedContainerColor = cs.surfaceVariant.copy(alpha = 0.4f),
            cursorColor = cs.primary,
            focusedTextColor = cs.onSurface,
            unfocusedTextColor = cs.onSurface
        ),
        leadingIcon = { Text("🔍", fontSize = 18.sp, modifier = Modifier.padding(start = 4.dp)) }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// App Row — Material 3 card style
// ─────────────────────────────────────────────────────────────────────────────
data class AppItem(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable, val locked: Boolean)

@Composable
fun AppRow(app: AppItem, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(
        targetValue = if (app.locked) cs.primaryContainer.copy(alpha = 0.6f) else cs.surface,
        animationSpec = tween(300), label = "appRowBg"
    )
    val elevation by animateFloatAsState(
        targetValue = if (app.locked) 2f else 0f,
        animationSpec = tween(300), label = "elevation"
    )

    Row(
        modifier = modifier.fillMaxWidth()
            .shadow(elevation.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable { onToggle() }
            .padding(12.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // App icon
        Image(
            bitmap = app.icon.toBitmap(44, 44).asImageBitmap(),
            contentDescription = app.label,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
        )

        // App info
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.label, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.pkg, color = cs.onSurfaceVariant, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Lock badge
        if (app.locked) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cs.primary,
                modifier = Modifier.height(28.dp)
            ) {
                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Bloqueada", color = cs.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceVariant,
                modifier = Modifier.height(28.dp)
            ) {
                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Libre", color = cs.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Config Tab
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigTab(
    a11yOk: Boolean, notifOk: Boolean, overlayOk: Boolean, adminOk: Boolean, batteryOk: Boolean,
    hideNotif: Boolean,
    relockDelay: PrefsManager.RelockDelay,
    appTheme: PrefsManager.AppTheme,
    requireAuthToOpen: Boolean,
    onHide: (Boolean) -> Unit,
    onRelockDelay: (PrefsManager.RelockDelay) -> Unit,
    onAppTheme: (PrefsManager.AppTheme) -> Unit,
    onRequireAuth: (Boolean) -> Unit,
    onA11y: () -> Unit, onNotif: () -> Unit, onOverlay: () -> Unit, onUsage: () -> Unit, onAdmin: () -> Unit, onBattery: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // ── Permission section ───────────────────────────────────────────────
        SectionHeader("Permisos")

        PermissionCard(
            emoji = "♿", title = "Accesibilidad",
            body = "Permite a AppLock saber cuándo abres otra app. Sin esto, es imposible bloquear las aplicaciones.",
            ok = a11yOk, okLabel = "Activo", failLabel = "Requerido", onClick = onA11y
        )
        PermissionCard(
            emoji = "🔔", title = "Notificaciones",
            body = "Permite ocultar el contenido de los mensajes (ej. WhatsApp) en la barra de estado para proteger tu privacidad.",
            ok = notifOk, okLabel = "Activo", failLabel = "Requerido", onClick = onNotif
        )
        PermissionCard(
            emoji = "⚡", title = "Mostrar sobre otras apps",
            body = "Permite dibujar la pantalla de bloqueo instantáneamente encima de la app protegida, evitando que se vea el contenido por un segundo.",
            ok = overlayOk, okLabel = "Activo", failLabel = "Requerido", onClick = onOverlay
        )
        PermissionCard(
            emoji = "📊", title = "Uso de aplicaciones",
            body = "Ayuda complementaria a Accesibilidad para que el bloqueo sea ultra rápido y preciso al detectar cambios de pantalla.",
            ok = true, okLabel = "Activo", failLabel = "Activar", onClick = onUsage
        )
        PermissionCard(
            emoji = "🔋", title = "Ahorro de Batería",
            body = "Evita que Samsung One UI mate el servicio de seguridad en segundo plano.",
            ok = batteryOk, okLabel = "Excluido", failLabel = "Excluir", onClick = onBattery
        )
        PermissionCard(
            emoji = "🛡️", title = "Protección Desinstalación",
            body = "Evita que alguien simplemente desinstale AppLock1 para saltarse la seguridad. (Administrador de Dispositivo)",
            ok = adminOk, okLabel = "Protegido", failLabel = "Proteger", onClick = onAdmin
        )

        Spacer(Modifier.height(6.dp))

        // ── Relock Delay ─────────────────────────────────────────────────────
        SectionHeader("Tiempo de re-bloqueo")

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cs.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("⏰", fontSize = 20.sp)
                    Column {
                        Text("¿Cuándo volver a bloquear?",
                            color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Tiempo que puede pasar fuera de la app sin que pida clave al volver",
                            color = cs.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                // Chips grid — 3 per row
                val delays = PrefsManager.RelockDelay.entries
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    delays.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { option ->
                                val selected = relockDelay == option
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) cs.primary else cs.surface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onRelockDelay(option) }
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                                    ) {
                                        Text(option.emoji, fontSize = 18.sp)
                                        Text(
                                            option.label,
                                            color = if (selected) cs.onPrimary else cs.onSurface,
                                            fontSize = 10.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                            }
                            // Fill empty slots
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        
        SectionHeader("Tema Visual")
        Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val themeOptions = PrefsManager.AppTheme.values()
                var themeExpanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(expanded = themeExpanded, onExpandedChange = { themeExpanded = !themeExpanded }) {
                    OutlinedTextField(
                        value = "${appTheme.emoji}  ${appTheme.label}",
                        onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = cs.primary,
                            unfocusedBorderColor = cs.outline
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = themeExpanded, onDismissRequest = { themeExpanded = false }) {
                        themeOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text("${opt.emoji}  ${opt.label}", color = cs.onSurface) },
                                onClick = { onAppTheme(opt); themeExpanded = false }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── Notifications ────────────────────────────────────────────────────
        SectionHeader("Privacidad")

        Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant.copy(alpha = 0.5f),
            tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Bloquear AppLock1", color = cs.onSurface,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Pedir autenticación para abrir la app",
                            color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = requireAuthToOpen, onCheckedChange = onRequireAuth,
                        colors = SwitchDefaults.colors(checkedTrackColor = cs.primary))
                }
                HorizontalDivider(color = cs.outline.copy(alpha=0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Ocultar contenido de mensajes", color = cs.onSurface,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Las notificaciones mostrarán solo \"Nuevo mensaje\" en lugar del texto real",
                        color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = hideNotif, onCheckedChange = onHide,
                    colors = SwitchDefaults.colors(checkedTrackColor = cs.primary))
            }
            } // Close Column
        }

        Spacer(Modifier.height(6.dp))

        // ── Quick Settings tile ──────────────────────────────────────────────
        SectionHeader("Acceso rápido")

        Surface(shape = RoundedCornerShape(16.dp), color = cs.tertiaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(cs.tertiary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center) { Text("⚡", fontSize = 20.sp) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tile de notificaciones", color = cs.onTertiaryContainer,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Baja la cortina 2 veces → Editar → Añade \"AppLock1\" para activar o pausar sin abrir la app.",
                        color = cs.onTertiaryContainer.copy(alpha = 0.75f), fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("AppLock1 v1.6", color = cs.outlineVariant, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SectionHeader(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(text.uppercase(), color = cs.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
fun PermissionCard(emoji: String, title: String, body: String, ok: Boolean,
                   okLabel: String, failLabel: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bgColor = if (ok) cs.secondaryContainer.copy(alpha = 0.5f) else cs.errorContainer.copy(alpha = 0.3f)
    val labelColor = if (ok) cs.onSecondaryContainer else cs.onErrorContainer
    val chipBg = if (ok) cs.secondary else cs.error
    val chipText = if (ok) cs.onSecondary else cs.onError

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Icon circle
            Box(Modifier.size(46.dp).clip(CircleShape)
                .background(if (ok) cs.secondary.copy(alpha = 0.15f) else cs.error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }

            // Text
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = labelColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = labelColor.copy(alpha = 0.7f), fontSize = 12.sp)
            }

            // Status chip
            Surface(shape = RoundedCornerShape(20.dp), color = chipBg) {
                Text(if (ok) okLabel else failLabel, color = chipText, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}
