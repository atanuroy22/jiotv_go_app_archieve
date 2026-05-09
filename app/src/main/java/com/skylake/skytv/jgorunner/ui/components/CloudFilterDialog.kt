package com.skylake.skytv.jgorunner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun MultiSelectFilterDialog(
    title: String,
    options: List<String>,
    selectedOptions: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var currentSelection by remember { mutableStateOf(selectedOptions) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF333333),
            modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.8f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.Cyan,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(options) { option ->
                        val isSelected = currentSelection.contains(option)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentSelection = if (isSelected) {
                                        currentSelection - option
                                    } else {
                                        currentSelection + option
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    currentSelection = if (it) {
                                        currentSelection + option
                                    } else {
                                        currentSelection - option
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Color.Cyan)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = option, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { onConfirm(currentSelection) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                    ) {
                        Text("Apply", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
