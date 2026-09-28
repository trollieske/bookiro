package com.bookrio.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bookrio brand colours (shared with the Android HUD theme).
 * The full library/designsystem Compose UI is migrated here in a later phase;
 * this entry proves the Compose Multiplatform + static-framework pipeline and
 * gives iPhone/iPad a real first screen.
 */
private val Bg = Color(0xFF000000)
private val Panel = Color(0xFF0D0D0D)
private val Accent = Color(0xFFBEF93F)
private val Dim = Color(0xFF8C8C8C)
private val Fg = Color(0xFFF5F5F5)

@Composable
fun App() {
    MaterialTheme {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Accent, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("B", color = Bg, fontSize = 40.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "BOOKRIO",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = 8.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Your ebooks, audiobooks and podcasts.\niPhone & iPad port in progress.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    "KMP · Compose Multiplatform · AVPlayer · PDFKit",
                    color = Fg.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}