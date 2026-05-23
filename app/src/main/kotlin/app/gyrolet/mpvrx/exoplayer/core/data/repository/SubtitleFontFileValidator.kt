package app.gyrolet.mpvrx.exoplayer.core.data.repository

import java.io.File

class SubtitleFontFileValidator {
    fun validate(file: File) {
        // Basic validation: check if file is not empty
        if (file.length() == 0L) {
            throw IllegalArgumentException("Font file is empty")
        }
        // More advanced validation could check font headers (e.g., TTF/OTF magic numbers)
    }
}
