package com.jakala.gekko.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

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
    Column(Modifier.padding(16.dp).semantics { testTagsAsResourceId = true }) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.testTag("sample.name"),
        )
        Button(onClick = { greeting = "Hello $name" }, modifier = Modifier.testTag("sample.greet")) { Text("Greet") }
        Text(greeting, modifier = Modifier.testTag("sample.greeting"))
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
