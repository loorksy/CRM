package cloud.lork.auditsend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import cloud.lork.auditsend.ui.AuditTheme
import cloud.lork.auditsend.ui.CycleListScreen
import cloud.lork.auditsend.ui.CycleScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuditTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "cycles") {
                    composable("cycles") {
                        CycleListScreen(onOpen = { id -> nav.navigate("cycle/$id") })
                    }
                    composable(
                        route = "cycle/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: return@composable
                        CycleScreen(cycleId = id, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
