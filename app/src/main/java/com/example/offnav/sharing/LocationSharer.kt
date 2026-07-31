package com.example.offnav.sharing

import android.content.Context
import android.content.Intent
import android.location.Location

object LocationSharer {

    fun shareCurrentLocation(context: Context, location: Location, label: String = "") {
        val lat = location.latitude
        val lon = location.longitude
        val accuracy = if (location.hasAccuracy()) " (±${location.accuracy.toInt()}m)" else ""

        val text = buildString {
            if (label.isNotBlank()) appendLine(label)
            appendLine("📍 ${"%.6f".format(lat)}, ${"%.6f".format(lon)}$accuracy")
            appendLine()
            appendLine("Google Maps: https://maps.google.com/?q=$lat,$lon")
            appendLine("OpenStreetMap: https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "My Location")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share location via"))
    }

    fun shareRoute(context: Context, from: Location, destinationLabel: String,
                   destLat: Double, destLon: Double,
                   distanceText: String, durationText: String) {
        val text = buildString {
            appendLine("🧭 Navigating to: $destinationLabel")
            appendLine("From: ${"%.6f".format(from.latitude)}, ${"%.6f".format(from.longitude)}")
            appendLine("To: ${"%.6f".format(destLat)}, ${"%.6f".format(destLon)}")
            appendLine("Distance: $distanceText · ETA: $durationText")
            appendLine()
            appendLine("https://maps.google.com/maps?saddr=${from.latitude},${from.longitude}&daddr=$destLat,$destLon")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Route to $destinationLabel")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share route via"))
    }
}