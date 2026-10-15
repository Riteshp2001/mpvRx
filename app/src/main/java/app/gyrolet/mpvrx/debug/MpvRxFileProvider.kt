/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.debug

import androidx.core.content.FileProvider

/**
 * Give mpvRx's share provider its own component identity.
 *
 * DebugOverlay also declares androidx.core.content.FileProvider, but has a separate
 * authority and separate path mapping. Using this subclass prevents manifest merger
 * from combining the two provider declarations and preserves both export paths.
 */
class MpvRxFileProvider : FileProvider()
