package com.kimyubin33.audioapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kimyubin33.audioapp.R

// 글꼴 (둘 다 SIL OFL, 앱에 내장. 라이선스: docs/licenses/)
/** 붓글씨체 (나눔 붓글씨). 로고와 화면의 큰 제목에만 아껴서 쓴다. */
val BrushFont = FontFamily(Font(R.font.nanum_brush_script))

/** 단정한 바탕체 (고운바탕). 곡 제목처럼 읽는 글에 쓴다. */
val SerifFont = FontFamily(Font(R.font.gowun_batang))

val Typography = Typography(
    // 붓글씨: 로고, 화면 제목
    displaySmall = TextStyle(fontFamily = BrushFont, fontSize = 38.sp, lineHeight = 44.sp),
    headlineSmall = TextStyle(fontFamily = BrushFont, fontSize = 32.sp, lineHeight = 38.sp),
    // 바탕체: 곡 제목
    headlineMedium = TextStyle(fontFamily = SerifFont, fontSize = 24.sp, lineHeight = 34.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = SerifFont, fontSize = 20.sp, lineHeight = 30.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = SerifFont, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    // 고딕(기본): 보조 정보와 버튼. 글자 사이를 조금 띄워서 차분하게
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)
