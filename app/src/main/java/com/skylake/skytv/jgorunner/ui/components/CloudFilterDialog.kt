package com.skylake.skytv.jgorunner.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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
    singleSelect: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var currentSelection by remember { mutableStateOf(selectedOptions) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF222222),
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f).border(1.dp, Color.Gray, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.Cyan,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(options) { option ->
                        FilterItemRow(
                            label = option,
                            isSelected = currentSelection.contains(option),
                            onToggle = { selected ->
                                currentSelection = if (singleSelect) {
                                    if (selected) setOf(option) else emptySet()
                                } else {
                                    if (selected) {
                                        currentSelection + option
                                    } else {
                                        currentSelection - option
                                    }
                                }
                            }
                        )
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

@Composable
fun FilterItemRow(
    label: String,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(if (isFocused) Color.Cyan.copy(alpha = 0.1f) else Color.Transparent)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onToggle(!isSelected) }
            .focusable()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle(it) },
            colors = CheckboxDefaults.colors(
                checkedColor = Color.Cyan,
                uncheckedColor = if (isFocused) Color.White else Color.Gray
            ),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = if (isFocused) Color.Cyan else Color.White,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
