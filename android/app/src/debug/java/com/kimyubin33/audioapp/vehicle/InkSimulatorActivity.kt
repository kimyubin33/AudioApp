package com.kimyubin33.audioapp.vehicle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimyubin33.audioapp.ui.vehicle.*

/**
 * Debug-only, parked activity. No distractionOptimized declaration and no incoming extras.
 * The navigation map, track titles, location and driving toggle are synthetic design fixtures.
 * This activity never invokes playback, recommendations or location APIs.
 */
class InkSimulatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { InkSimulator() }
    }
}

@Composable
internal fun InkSimulator(initialReducedMotion: Boolean = false) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var playing by rememberSaveable { mutableStateOf(true) }
    var driving by rememberSaveable { mutableStateOf(false) }
    var reduced by rememberSaveable { mutableStateOf(initialReducedMotion) }
    var wide by rememberSaveable { mutableStateOf(false) }
    var fallback by rememberSaveable { mutableStateOf(false) }
    var eventToken by rememberSaveable { mutableIntStateOf(0) }
    MaterialTheme(colorScheme=darkColorScheme(primary=WaterPaper,background=WaterInk,surface=WaterInk)) {
        Column(Modifier.fillMaxSize().background(WaterInk).safeDrawingPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("INK / STUDIO",color=WaterPaper,fontSize=12.sp,letterSpacing=2.sp)
                FilterChip(driving,{driving=!driving},label={Text(if(driving)"Driving" else "Parked")})
                FilterChip(wide,{wide=!wide},label={Text(if(wide)"Wide" else "Split")})
                FilterChip(reduced,{reduced=!reduced},label={Text("Still")})
                FilterChip(fallback,{fallback=!fallback},label={Text("Canvas")})
                TextButton(onClick={eventToken++;playing=true}) { Text("Drop") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val scene=sampleInkScene(selected,playing,driving).copy(reducedMotion=reduced,eventToken=eventToken)
                if(maxWidth >= 700.dp && !wide) {
                    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        NavigationFixture(Modifier.weight(1.35f).fillMaxHeight())
                        VehicleAudioPanel(scene,{playing=!playing},{selected=(selected+1)%3},
                            Modifier.weight(1f).fillMaxHeight(),forceFallback=fallback)
                    }
                } else {
                    VehicleAudioPanel(scene,{playing=!playing},{selected=(selected+1)%3},
                        Modifier.fillMaxSize(),forceFallback=fallback)
                }
            }
            Text("DESIGN SIMULATION · SAMPLE DATA · NO AUDIO / GUIDANCE",color=WaterMuted,
                fontSize=9.sp,letterSpacing=1.sp,modifier=Modifier.padding(top=8.dp,bottom=2.dp))
        }
    }
}

@Composable
private fun NavigationFixture(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF182124))) {
        Canvas(Modifier.fillMaxSize()) {
            val w=size.width; val h=size.height
            // Fictional streets drawn as a quiet background, never used for actual guidance.
            val river=Path().apply {
                moveTo(w*.68f,0f); cubicTo(w*.18f,h*.25f,w*.95f,h*.37f,w*.55f,h*.63f)
                cubicTo(w*.35f,h*.85f,w*.75f,h*.87f,w*.48f,h)
            }
            drawPath(river,Color(0xFF213E44),style=Stroke(w*.14f))
            repeat(9) { i ->
                val y=h*(i/8f)
                val path=Path().apply {
                    moveTo(-w*.2f,y+h*.1f)
                    cubicTo(w*.25f,y-h*.14f,w*.65f,y+h*.18f,w*1.1f,y-h*.13f)
                }
                drawPath(path,Color(0xFF354043),style=Stroke(2.dp.toPx()))
            }
            repeat(6) { i ->
                val x=w*i/5f
                val path=Path().apply {
                    moveTo(x-w*.16f,0f); cubicTo(x+w*.2f,h*.3f,x-w*.28f,h*.7f,x+w*.12f,h)
                }
                drawPath(path,Color(0xFF354043),style=Stroke(2.dp.toPx()))
            }
            val route=Path().apply {
                moveTo(w*.47f,h*.76f)
                cubicTo(w*.47f,h*.6f,w*.33f,h*.55f,w*.36f,h*.43f)
                cubicTo(w*.39f,h*.33f,w*.55f,h*.35f,w*.52f,h*.19f)
            }
            drawPath(route,Color(0xFF507F81),style=Stroke(16.dp.toPx(),cap=StrokeCap.Round))
            drawPath(route,Color(0xFFA5D9CC),style=Stroke(5.dp.toPx(),cap=StrokeCap.Round))
            val position=Offset(w*.47f,h*.76f)
            drawCircle(Color(0xFFA5D9CC).copy(alpha=.12f),28.dp.toPx(),position)
            val arrow=Path().apply {
                moveTo(position.x,position.y-13.dp.toPx())
                lineTo(position.x+10.dp.toPx(),position.y+10.dp.toPx())
                lineTo(position.x,position.y+5.dp.toPx())
                lineTo(position.x-10.dp.toPx(),position.y+10.dp.toPx());close()
            }
            drawPath(arrow,WaterPaper)
        }
        Column(Modifier.align(Alignment.TopStart).padding(28.dp)) {
            Text("NAVIGATION / DEMO",color=WaterMuted,fontSize=10.sp,letterSpacing=2.sp)
            Spacer(Modifier.height(12.dp))
            Text("Follow the river",color=WaterPaper,fontFamily=FontFamily.Serif,fontSize=30.sp)
            Text("Sample route",color=WaterMuted,fontSize=12.sp)
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(
            Brush.verticalGradient(listOf(Color.Transparent,WaterInk))).padding(28.dp),
            horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Bottom) {
            Column {
                Text("18 min",color=WaterPaper,fontSize=28.sp)
                Text("8.4 km · Demo destination",color=WaterMuted,fontSize=12.sp)
            }
            Text("N ↑",color=WaterMuted,fontSize=13.sp)
        }
    }
}
