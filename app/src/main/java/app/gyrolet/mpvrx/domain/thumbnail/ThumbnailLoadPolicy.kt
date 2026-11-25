/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.thumbnail

/** Thumbnails generated at once: every core, up to 8. More parallel frame decodes only add memory use. */
internal fun thumbnailParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
