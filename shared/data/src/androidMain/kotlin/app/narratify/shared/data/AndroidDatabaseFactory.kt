package app.narratify.shared.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.narratify.shared.data.db.NarratifyDatabase

object AndroidDatabaseFactory {
    fun create(context: Context, name: String = "narratify.db"): NarratifyDatabase {
        val driver = AndroidSqliteDriver(
            schema = NarratifyDatabase.Schema,
            context = context.applicationContext,
            name = name,
        )
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return NarratifyDatabase(driver)
    }
}

