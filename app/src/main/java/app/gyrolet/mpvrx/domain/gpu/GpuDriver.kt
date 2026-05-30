package app.gyrolet.mpvrx.domain.gpu

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class GpuDriver(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val vendor: String = "",
    val driverPath: String, // Path to the extracted driver directory
    val vulkanLibName: String, // Usually libvulkan_freedreno.so
    val isSystem: Boolean = false,
    // Epoch millis when the driver was installed. 0 = unknown (older installs
    // from before this field existed). Surfaced as an info chip in the UI.
    val installDate: Long = 0L,
    // Total on-disk size of the extracted driver dir. 0 = not measured.
    val fileSizeBytes: Long = 0L,
    // Minimum Vulkan API version declared in meta.json (e.g. "1.1.0").
    // Display-only; not used for compatibility gating.
    val minApi: String = "",
)
