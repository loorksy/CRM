package cloud.lork.auditsend.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val Teal = Color(0xFF0F766E)
private val Ink = Color(0xFF0F172A)

@Composable
fun AuditTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Teal,
                onPrimary = Color.White,
                secondary = Color(0xFF1D4ED8),
                background = Color(0xFFF8FAFC),
                surface = Color.White,
                onBackground = Ink,
                onSurface = Ink,
            ),
            content = {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            },
        )
    }
}
