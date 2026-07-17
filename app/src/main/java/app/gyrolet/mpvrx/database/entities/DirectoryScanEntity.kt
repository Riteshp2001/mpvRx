package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
  tableName = "directory_scan_index",
  primaryKeys = ["scanKey", "path"],
  indices = [
    Index(value = ["scanKey", "rootPath"]),
    Index(value = ["scanKey", "isNoMediaRoot"]),
  ],
)
data class DirectoryScanEntity(
  val scanKey: String,
  val path: String,
  val rootPath: String,
  val fingerprint: String,
  val isNoMediaRoot: Boolean,
  val videoCount: Int,
  val totalSize: Long,
  val totalDuration: Long,
  val lastModified: Long,
  val hasSubfolders: Boolean,
  val lastScanned: Long,
)
