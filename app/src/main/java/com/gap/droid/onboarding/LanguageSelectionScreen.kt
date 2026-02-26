package com.gapmesh.droid.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Language selection screen shown on first app launch.
 * Allows user to choose between English and Farsi.
 */
@Composable
fun LanguageSelectionScreen(
    onLanguageSelected: (LanguagePreferenceManager.AppLanguage) -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            // App logo/title
            Text(
                text = "Gap Mesh/",
                fontFamily = FontFamily.Monospace,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )

            // Bilingual title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Choose Language",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onBackground
                )
                Text(
                    text = "زبان را انتخاب کنید",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Language buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // English button
                LanguageButton(
                    languageName = "English",
                    nativeName = "English",
                    onClick = {
                        LanguagePreferenceManager.setLanguage(context, LanguagePreferenceManager.AppLanguage.ENGLISH)
                        onLanguageSelected(LanguagePreferenceManager.AppLanguage.ENGLISH)
                    }
                )

                // Farsi button
                LanguageButton(
                    languageName = "Farsi",
                    nativeName = "فارسی",
                    onClick = {
                        LanguagePreferenceManager.setLanguage(context, LanguagePreferenceManager.AppLanguage.FARSI)
                        onLanguageSelected(LanguagePreferenceManager.AppLanguage.FARSI)
                    }
                )
            }
        }
    }
}

@Composable
private fun LanguageButton(
    languageName: String,
    nativeName: String,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 2.dp,
                color = colorScheme.primary,
                shape = RoundedCornerShape(12.dp)
            )
            .background(colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = nativeName,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary,
                textAlign = TextAlign.Center
            )
            if (languageName != nativeName) {
                Text(
                    text = languageName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
