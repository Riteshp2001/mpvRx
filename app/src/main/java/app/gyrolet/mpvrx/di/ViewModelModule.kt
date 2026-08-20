/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.di

import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Presentation-layer dependencies with lifecycle-aware ownership. */
val ViewModelModule =
  module {
    viewModelOf(::RecentlyPlayedViewModel)
  }
