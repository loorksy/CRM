package cloud.lork.audit

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val session = (application as LorkApp).session
        if (result.resultCode == RESULT_OK) session.completeFromConsent() else session.cancelFromConsent()
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale("ar")
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onStart() {
        super.onStart()
        (application as LorkApp).session.attachLauncher { pending ->
            consent.launch(IntentSenderRequest.Builder(pending).build())
        }
    }

    override fun onStop() {
        (application as LorkApp).session.detachLauncher()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}
