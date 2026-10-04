package com.grid.app.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.grid.app.R
import com.grid.app.core.designsystem.components.MonthGridIllustration
import com.grid.app.core.designsystem.components.gridBackground

/** Shown over the whole app while it's locked; asks for biometrics / device credential straight away. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0D0E)).gridBackground(Color.White.copy(alpha = 0.03f)).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MonthGridIllustration(Modifier.padding(horizontal = 40.dp))
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineMedium, color = Color(0xFFF2F4F3))
        Text(stringResource(R.string.lock_body), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF8E979B), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onUnlock,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC8F560), contentColor = Color(0xFF0B0D0E)),
        ) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.lock_unlock), modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
        }
    }
}
