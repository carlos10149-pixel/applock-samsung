package com.applock1.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import com.applock1.app.services.AppLockAccessibilityService
import com.applock1.app.services.FastShieldManager

class LockOverlayActivity : FragmentActivity() {

    companion object {
        const val PKG_KEY = "locked_pkg"
    }

    private var pkg: String? = null
    private var errorMsg by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var lastBack = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots, show on lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        pkg = intent.getStringExtra(PKG_KEY)
        if (pkg == null) { FastShieldManager.hide(); finish(); return }

        setContent {
            AppLockTheme {
                LockScreen(
                    pkg = pkg!!,
                    errorMsg = errorMsg,
                    busy = busy,
                    onUnlock = ::launchBiometric
                )
            }
        }

        // Show biometric dialog immediately
        window.decorView.post { launchBiometric() }
    }

    private var prompt: BiometricPrompt? = null

    private fun launchBiometric() {
        val p = pkg ?: return
        busy = true
        errorMsg = null

        val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val canAuth = BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                // Failsafe: if the user removes their PIN, do not lock them out forever.
                android.widget.Toast.makeText(this, "AppLock desactivado: Configura un PIN en Android", android.widget.Toast.LENGTH_LONG).show()
                AppLock1App.instance.prefs.isGlobalEnabled = false
                onSuccess()
                return
            }
            errorMsg = "Sin datos biométricos — toca Desbloquear para entrar"
            busy = false
            return
        }

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
        } catch (_: Exception) { p }

        prompt = BiometricPrompt(this, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                busy = false
                onSuccess()
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                busy = false
                when (code) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> goHome()
                    else -> errorMsg = msg.toString()
                }
            }
            override fun onAuthenticationFailed() {
                errorMsg = "Huella no reconocida. Intenta de nuevo."
            }
        })

        prompt?.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verificar identidad")
                .setSubtitle("Para acceder a $appLabel")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun onSuccess() {
        val p = pkg ?: return
        AppLockAccessibilityService.onUnlocked(p)
        FastShieldManager.hide()
        finish()
    }

    private fun goHome() {
        prompt?.cancelAuthentication()
        FastShieldManager.hide()
        AppLockAccessibilityService.resetLock()
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    @Deprecated("Use predictive back")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) goHome() else lastBack = now
    }

    override fun onStop() {
        super.onStop()
        // BUG FIX: Samsung BiometricPrompt often leaks if the activity is stopped
        // (e.g. screen turns off or phone call comes in). Canceling explicitly releases the sensor.
        prompt?.cancelAuthentication()
        busy = false
    }

    override fun onDestroy() {
        super.onDestroy()
        FastShieldManager.hide()
        AppLockAccessibilityService.resetLock()
    }
}

@Composable
fun LockScreen(pkg: String, errorMsg: String?, busy: Boolean, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val appName = remember(pkg) {
        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (_: Exception) { pkg }
    }
    val icon = remember(pkg) {
        try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(88, 88).asImageBitmap() }
        catch (_: Exception) { null }
    }

    Box(
        Modifier.fillMaxSize().background(cs.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            // Glow effect lock icon
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(cs.primary.copy(alpha = 0.1f), CircleShape)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("🔒", fontSize = 48.sp)
            }

            if (icon != null) {
                Image(icon, appName, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
            }

            Text(appName, color = cs.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Surface(shape = RoundedCornerShape(24.dp), color = cs.primary.copy(alpha = 0.15f)) {
                Text(
                    "App Protegida",
                    color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (errorMsg != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cs.errorContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        errorMsg, color = cs.error, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onUnlock, enabled = !busy,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = cs.primary, contentColor = cs.onPrimary
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = cs.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (busy) "Verificando..." else "Desbloquear",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
