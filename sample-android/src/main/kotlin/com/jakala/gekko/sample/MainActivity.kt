package com.jakala.gekko.sample

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SampleApp() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SampleApp() {
    var name by rememberSaveable { mutableStateOf("") }
    var greeting by rememberSaveable { mutableStateOf("") }
    var gesture by rememberSaveable { mutableStateOf("none") }
    var location by rememberSaveable { mutableStateOf("unknown") }
    val context = LocalContext.current
    Column(Modifier.padding(16.dp).semantics { testTagsAsResourceId = true }) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.testTag("sample.name"),
        )
        // The location UI shares the rows of the greeting, so the list below keeps its place
        Row {
            Button(onClick = { greeting = "Hello $name" }, modifier = Modifier.testTag("sample.greet")) {
                Text("Greet")
            }
            Button(onClick = { locate(context) { location = it } }, modifier = Modifier.testTag("sample.locate")) {
                Text("Locate")
            }
        }
        Row {
            Text(greeting, modifier = Modifier.testTag("sample.greeting"))
            Text("Location: $location", modifier = Modifier.padding(start = 16.dp).testTag("sample.location"))
        }
        Text(
            "Gesture target",
            modifier =
                Modifier.testTag("sample.gesture")
                    .combinedClickable(
                        onClick = { gesture = "tap" },
                        onDoubleClick = { gesture = "double tap" },
                        onLongClick = { gesture = "long press" },
                    )
                    .padding(8.dp),
        )
        Text("Gesture: $gesture", modifier = Modifier.testTag("sample.gesture.result"))
        LazyColumn(Modifier.testTag("sample.list")) {
            items((1..60).toList()) { index -> Text("Row $index", modifier = Modifier.testTag("sample.row.$index")) }
        }
    }
}

/** Shows the last GPS fix and asks for the next one, so the end-to-end tests can see a mocked location. */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun locate(context: Context, show: (String) -> Unit) {
    val manager = checkNotNull(context.getSystemService(LocationManager::class.java))
    val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                show(String.format(Locale.US, "%.4f,%.4f", location.latitude, location.longitude))
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }
    // The last fix is there at once, the next one a moment later
    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(listener::onLocationChanged)
    manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
}
