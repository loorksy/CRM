package cloud.lork.auditsend

import android.app.Application
import cloud.lork.auditsend.data.AppDatabase

class AuditApplication : Application() {
    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
    }
}
