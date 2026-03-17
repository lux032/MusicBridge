package com.lux032.plextosonosplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lux032.plextosonosplayer.plex.PlexAlbumResult
import com.lux032.plextosonosplayer.plex.PlexAuthConfig
import com.lux032.plextosonosplayer.plex.PlexClient
import com.lux032.plextosonosplayer.ui.theme.PlexToSonosPlayerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlexToSonosPlayerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlexAlbumScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun PlexAlbumScreen(modifier: Modifier = Modifier) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var albumResult by remember { mutableStateOf<PlexAlbumResult?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Plex 专辑抓取测试")
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex 用户名") },
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex 密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex Token（可选，优先）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器名称（可选）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex Base URL（可选）") },
            singleLine = true,
        )
        Button(
            onClick = {
                isLoading = true
                errorMessage = null
                albumResult = null
                scope.launch {
                    runCatching {
                        PlexClient(
                            PlexAuthConfig(
                                username = username,
                                password = password,
                                preferredServer = server,
                                baseUrl = baseUrl,
                                token = token,
                            )
                        ).fetchAlbumNames()
                    }.onSuccess {
                        albumResult = it
                    }.onFailure {
                        errorMessage = it.message ?: "未知错误"
                    }
                    isLoading = false
                }
            },
            enabled = !isLoading,
        ) {
            Text("获取 Plex 专辑名称")
        }

        if (isLoading) {
            CircularProgressIndicator()
        }

        errorMessage?.let {
            Text("错误: $it")
        }

        albumResult?.let { result ->
            Text("服务器: ${result.serverName}")
            Text("音乐库: ${result.sections.joinToString { it.title }}")
            Text("专辑数: ${result.albums.size}")
            Text(result.albums.joinToString(separator = "\n"))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PlexToSonosPlayerTheme {
        PlexAlbumScreen()
    }
}
