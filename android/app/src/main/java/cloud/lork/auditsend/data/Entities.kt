package cloud.lork.auditsend.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cycles")
data class CycleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val auditOpen: Boolean = true,
    val userInfoUserIdCol: String = "C",
    val userInfoTitleCol: String = "D",
    val userInfoSalaryCol: String = "L",
    val userInfoStatusCol: String = "M",
    val userInfoPhoneCol: String = "E",
    val userInfoMessageCol: String = "F",
    val mgmtUserIdCol: String = "A",
    val agentUserIdCol: String = "A",
    val agentSalaryCol: String = "D",
    val discountRatePct: Double = 0.0,
    val agentColor: String = "#8b5cf6",
    val managementColor: String = "#facc15",
    val lastSummary: String = "",
)

@Entity(
    tableName = "sheets",
    primaryKeys = ["cycleId", "kind"],
    foreignKeys = [
        ForeignKey(
            entity = CycleEntity::class,
            parentColumns = ["id"],
            childColumns = ["cycleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("cycleId")],
)
data class SheetEntity(
    val cycleId: Long,
    val kind: String,
    val rowsJson: String,
    val colorsJson: String = "{}",
)

object SheetKind {
    const val USER_INFO = "user_info"
    const val MANAGEMENT = "management"
    const val AGENT = "agent"
    val all = listOf(USER_INFO, MANAGEMENT, AGENT)
    fun label(kind: String) = when (kind) {
        USER_INFO -> "معلومات المستخدمين"
        MANAGEMENT -> "الإدارة"
        AGENT -> "الوكيل"
        else -> kind
    }
}
