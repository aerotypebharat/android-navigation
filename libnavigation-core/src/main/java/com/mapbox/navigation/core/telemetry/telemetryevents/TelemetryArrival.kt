package com.mapbox.navigation.core.telemetry.telemetryevents

import android.annotation.SuppressLint
import android.os.Parcel
import com.mapbox.android.telemetry.Event

/**
 * Documentation is here [https://paper.dropbox.com/doc/Navigation-Telemetry-Events-V1--AuUz~~~rEVK7iNB3dQ4_tF97Ag-iid3ZImnt4dsW7Z6zC3Lc]
 */

// Defaulted values are optional
@SuppressLint("ParcelCreator")
class TelemetryArrival(val Metadata: TelemetryMetadata) : Event() {
    val event = "navigation.arrive"
    override fun writeToParcel(dest: Parcel?, flags: Int) {
    }

    override fun describeContents() = 0
}
