package cn.a10miaomiao.bilidown.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import cn.a10miaomiao.bilidown.common.BiliDownOutFile
import cn.a10miaomiao.bilidown.common.FileNameUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileNameInputDialog(
    showInputDialog: Boolean,
    fileName: String,
    extension: String = "mp4",
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (outFile: BiliDownOutFile) -> Unit,
) {
    var errorText by remember() {
        mutableStateOf("")
    }
    var value by remember(fileName) {
        mutableStateOf(TextFieldValue(text = fileName, selection = TextRange(fileName.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(showInputDialog) {
        if (showInputDialog) {
            launch {
                focusRequester.requestFocus()
            }
        }
    }

    fun handleConfirm() {
        val name = value.text + ".$extension"
        if (name.isBlank()) {
            errorText = "文件名不能为空"
        } else if (!FileNameUtils.isValid(name)) {
            errorText = "文件名含有不支持的字符或是系统保留名称"
        } else {
            val outFile = BiliDownOutFile(name)
            if (outFile.exists()) {
                errorText = "文件已存在"
            } else {
                onConfirm(outFile)
            }
        }
    }

    fun handleSanitizeName() {
        val text = FileNameUtils.sanitize(value.text)
        value = TextFieldValue(
            text = text,
            selection = TextRange(text.length)
        )
    }

    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = "输入文件名：") },
            text = {
                TextField(
                    label = {
                        Text(text = "文件名")
                    },
                    trailingIcon = {
                        Text(text = ".$extension")
                    },
                    supportingText = {
                        Text(text = errorText)
                    },
                    isError = errorText.isNotBlank(),
                    value = value,
                    onValueChange = {
                        value = it
                        errorText = ""
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { handleConfirm() }
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = ::handleConfirm,
                ) {
                    Text(confirmText)
                }
            },
            dismissButton = {
                Row() {
                    if (FileNameUtils.sanitize(value.text) != value.text) {
                        TextButton(
                            onClick = ::handleSanitizeName,
                        ) {
                            Text("清理非法字符")
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                    ) {
                        Text("取消")
                    }
                }
            }
        )
    }
}
