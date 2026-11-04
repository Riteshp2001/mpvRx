/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.telemetry

import app.gyrolet.mpvrx.BuildConfig
import okhttp3.OkHttpClient
import sh.measure.android.okhttp.MeasureEventListenerFactory
import sh.measure.android.okhttp.MeasureOkHttpApplicationInterceptor

/**
 * Call once on a fresh builder; derived clients inherit both hooks via newBuilder().
 * These public APIs avoid Measure's version-limited rewrite of OkHttp's internal constructor.
 * Collection follows Measure's existing initialization and opt-in start/stop lifecycle.
 */
internal fun OkHttpClient.Builder.withMeasureTelemetry(): OkHttpClient.Builder = apply {
  if (BuildConfig.MEASURE_CONFIGURED) {
    eventListenerFactory(MeasureEventListenerFactory(null))
    addInterceptor(MeasureOkHttpApplicationInterceptor())
  }
}
