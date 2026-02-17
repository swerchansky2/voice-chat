package com.voicechat.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.client.ui.theme.AppColors
import com.voicechat.client.viewmodel.ConnectionState
import com.voicechat.client.viewmodel.VoiceChatViewModel

@Composable
fun ConnectScreen(
    viewModel: VoiceChatViewModel,
    onConnected: (String) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var serverHost by remember { mutableStateOf("localhost") }
    var serverPort by remember { mutableStateOf("8080") }
    
    val connectionState by viewModel.connectionState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    // Navigate when connected
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            onConnected(nickname)
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Voice Chat",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Nickname") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    focusedBorderColor = AppColors.Accent,
                    unfocusedBorderColor = AppColors.TextSecondary,
                    focusedLabelColor = AppColors.Accent,
                    unfocusedLabelColor = AppColors.TextSecondary
                )
            )
            
            OutlinedTextField(
                value = serverHost,
                onValueChange = { serverHost = it },
                label = { Text("Server Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    focusedBorderColor = AppColors.Accent,
                    unfocusedBorderColor = AppColors.TextSecondary,
                    focusedLabelColor = AppColors.Accent,
                    unfocusedLabelColor = AppColors.TextSecondary
                )
            )
            
            OutlinedTextField(
                value = serverPort,
                onValueChange = { serverPort = it },
                label = { Text("Server Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    focusedBorderColor = AppColors.Accent,
                    unfocusedBorderColor = AppColors.TextSecondary,
                    focusedLabelColor = AppColors.Accent,
                    unfocusedLabelColor = AppColors.TextSecondary
                )
            )
            
            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = AppColors.Danger,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Button(
                onClick = {
                    viewModel.clearError()
                    val port = serverPort.toIntOrNull() ?: 8080
                    viewModel.connect(nickname, serverHost, port)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = nickname.isNotBlank() && 
                         connectionState !is ConnectionState.Connecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = AppColors.TextSecondary
                )
            ) {
                if (connectionState is ConnectionState.Connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = androidx.compose.ui.graphics.Color.White
                    )
                } else {
                    Text("Connect", fontSize = 16.sp)
                }
            }
        }
    }
}
