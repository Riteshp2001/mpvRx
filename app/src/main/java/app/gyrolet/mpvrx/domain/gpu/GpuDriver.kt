package app.gyrolet.mpvrx.domain.gpu

import kotlinx.serialization.Serializable

@Serializable
data class GpuDriver(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val vendor: String = "",
    val driverPath: String, // Path to the extracted driver directory
    val vulkanLibName: String, // Usually libvulkan_freedreno.so
    val isSystem: Boolean = false
)
