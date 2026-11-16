/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.di

import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import app.gyrolet.mpvrx.domain.download.DownloadLocations
import app.gyrolet.mpvrx.domain.download.DownloadOutputReservations
import app.gyrolet.mpvrx.domain.download.LinkDownloadCoordinator
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinOfflineSyncManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val DownloadModule =
  module {
    single { DownloadLocations(androidContext(), get()) }
    single { DownloadOutputReservations(get<app.gyrolet.mpvrx.database.MpvRxDatabase>().downloadItemDao()) }
    single { AppDownloadManager(androidContext(), get<app.gyrolet.mpvrx.database.MpvRxDatabase>().downloadItemDao(), get(), get()) }
    single { YtdlpDownloadEngine(androidContext(), get(), get()) }
    single { LinkDownloadCoordinator(get(), get()) }
    single {
      JellyfinOfflineSyncManager(
        androidContext(),
        get<app.gyrolet.mpvrx.database.MpvRxDatabase>().downloadItemDao(),
        get(),
        get(),
      )
    }
  }
