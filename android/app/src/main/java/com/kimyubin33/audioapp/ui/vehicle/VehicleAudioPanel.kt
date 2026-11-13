package com.kimyubin33.audioapp.ui.vehicle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.kimyubin33.audioapp.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val WaterPaper = Color(0xFFE8EBDD)
internal val WaterMuted = Color(0xFFA9BCB4)
internal val WaterInk = Color(0xFF091619)

/** OEM surface only. Host decides whether and where this custom UI may be displayed. */
@Composable
fun VehicleAudioPanel(
    scene: VehicleAudioScene,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    forceFallback: Boolean = false,
) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(28.dp)).background(WaterInk)) {
        val compact = maxHeight < 340.dp
        val padding = if(compact) 20.dp else 28.dp
        InkSurface(scene, Modifier.fillMaxSize(), forceFallback)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to WaterInk.copy(alpha=.24f), .46f to Color.Transparent,
            .74f to WaterInk.copy(alpha=.72f), 1f to WaterInk.copy(alpha=.98f))))
        Row(Modifier.fillMaxWidth().padding(padding), verticalAlignment=Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_brand_mark),"AI & ME",Modifier.size(64.dp,36.dp),colorFilter=ColorFilter.tint(WaterPaper))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(5.dp).background(Color(0xFFB4C8AB),CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("DRIVE",color=WaterMuted,fontSize=10.sp,letterSpacing=2.sp)
        }
        // Labels are deliberately sparse and stationary. The ink itself carries the spatial detail.
        if(!compact) {
            Column(Modifier.align(Alignment.CenterStart).padding(start=padding,bottom=60.dp)) {
                Text("NEARBY",color=WaterMuted,fontSize=10.sp,letterSpacing=2.sp)
                Text(scene.regions.size.toString().padStart(2,'0'),color=WaterPaper.copy(alpha=.8f),
                    fontFamily=FontFamily.Serif,fontSize=36.sp,fontWeight=FontWeight.Light)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(padding)) {
            Text(if(scene.playing) "LISTENING" else "STILL WATER",color=WaterMuted,
                fontSize=10.sp,letterSpacing=2.sp)
            Spacer(Modifier.height(if(compact) 6.dp else 12.dp))
            AnimatedContent(scene.title,transitionSpec={
                fadeIn(tween(if(scene.reducedMotion) 0 else 700)) togetherWith fadeOut(tween(if(scene.reducedMotion) 0 else 350))
            },label="Water track") { title ->
                Text(title,color=WaterPaper,fontFamily=FontFamily.Serif,
                    fontWeight=FontWeight.Normal,fontSize=if(compact) 24.sp else 32.sp,
                    lineHeight=36.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text(scene.subtitle,color=WaterMuted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(if(compact) 12.dp else 22.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Button(onClick=onPlayPause,modifier=Modifier.weight(1f).heightIn(min=56.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=WaterPaper,contentColor=WaterInk),
                    shape=RoundedCornerShape(18.dp)) { Text(if(scene.playing) "Pause" else "Play",fontSize=16.sp) }
                OutlinedButton(onClick=onNext,modifier=Modifier.weight(1f).heightIn(min=56.dp),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=WaterPaper),
                    shape=RoundedCornerShape(18.dp)) { Text("Next",fontSize=16.sp) }
            }
        }
    }
}

internal fun sampleInkScene(selected: Int = 0, playing: Boolean = true, restricted: Boolean = false) =
    VehicleAudioScene(
        regions=listOf(InkRegion("river",.60f,.34f,.27f,1.7f),
            InkRegion("forest",.25f,.21f,.16f,5.2f),InkRegion("coast",.83f,.12f,.12f,9.4f)),
        selectedId=listOf("river","forest","coast")[selected.coerceIn(0,2)],
        title=listOf("River, slowly","Into the woods","A quieter coast")[selected.coerceIn(0,2)],
        subtitle=listOf("Riverside · 3.2 km","Forest · 2.1 km","Coast · 4.8 km")[selected.coerceIn(0,2)],
        playing=playing,restricted=restricted,
    )

@Preview(name="OEM narrow panel",widthDp=380,heightDp=600)
@Preview(name="OEM wide panel",widthDp=760,heightDp=440)
@Composable
private fun WaterPanelPreview() {
    MaterialTheme { VehicleAudioPanel(sampleInkScene(),{},{},Modifier.fillMaxSize(),forceFallback=true) }
}
