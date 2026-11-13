package com.kimyubin33.audioapp.ui.vehicle

import android.animation.ValueAnimator
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.*

/** Normalized presentation coordinates, NOT GPS. An OEM adapter projects its region data here.
 * IDs and seeds must be stable across recommendation updates. Maximum six visible regions.
 */
data class InkRegion(val id: String, val x: Float, val y: Float, val radius: Float, val seed: Float)

data class VehicleAudioScene(
    val regions: List<InkRegion>,
    val selectedId: String?,
    val title: String,
    val subtitle: String,
    val playing: Boolean,
    val restricted: Boolean,
    val reducedMotion: Boolean = false,
    val eventToken: Int = 0,
)

/** Renderer has no network, location, recommendation or player dependency. */
@Composable
fun InkSurface(scene: VehicleAudioScene, modifier: Modifier = Modifier, forceFallback: Boolean = false) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val time = remember { mutableFloatStateOf(.01f) }
    val eventStart = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(scene.selectedId, scene.eventToken) { eventStart.floatValue = time.floatValue }
    val intensity = animateFloatAsState(if(scene.restricted) .12f else 1f, tween(1200), label="Impact restraint")
    val speed = animateFloatAsState(
        if (!visible || !scene.playing || scene.reducedMotion) 0f else if (scene.restricted) .12f else 1f,
        tween(1800), label = "Water settling",
    )
    // Time is read only by the draw phase; the dashboard is not recomposed every frame.
    LaunchedEffect(visible, scene.reducedMotion) {
        if (!visible || scene.reducedMotion) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            if (!ValueAnimator.areAnimatorsEnabled() || speed.value < .001f) {
                last = 0L
                delay(100)
            } else {
                withFrameNanos { now ->
                    if (last != 0L) time.floatValue += ((now - last) / 1e9f).coerceAtMost(.05f) * speed.value
                    last = now
                }
            }
        }
    }
    val regions = scene.regions.take(6).map {
        it.copy(x = it.x.finite(.5f).coerceIn(0f, 1f), y = it.y.finite(.5f).coerceIn(0f, 1f),
            radius = it.radius.finite(.2f).coerceIn(.04f, .6f), seed = it.seed.finite(0f).coerceIn(-100f,100f))
    }
    val selected = regions.indexOfFirst { it.id == scene.selectedId }
    // Identity is discrete: interpolating IDs would teleport a falling drop between regions.
    val emphasis = rememberUpdatedState(selected.toFloat())
    val semantics = modifier.semantics {
        contentDescription = "먹물 수면, 오디오 영역 ${regions.size}개, " +
            if (scene.restricted) "주행 표현" else "정차 표현"
    }
    if (Build.VERSION.SDK_INT >= 33 && !LocalInspectionMode.current && !forceFallback) {
        ShaderSurface(regions, emphasis, time, eventStart, intensity, semantics)
    } else {
        Canvas(semantics) { drawInkFallback(regions, selected, time.floatValue, eventStart.floatValue, intensity.value) }
    }
}

private fun Float.finite(fallback: Float) = if (isFinite()) this else fallback

@RequiresApi(33)
@Composable
private fun ShaderSurface(regions: List<InkRegion>, selected: State<Float>, time: State<Float>, eventStart: State<Float>, intensity: State<Float>, modifier: Modifier) {
    // A compile failure degrades to the portable renderer instead of crashing the playback UI.
    val shader = remember { runCatching { RuntimeShader(INK_SHADER) }.getOrNull() }
    val brush = remember(shader) { shader?.let { ShaderBrush(it) } }
    Canvas(modifier) {
        if (shader == null || brush == null) {
            drawInkFallback(regions, selected.value.roundToInt(), time.value, eventStart.value, intensity.value)
        } else {
            shader.setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
            shader.setFloatUniform("time", time.value)
            shader.setFloatUniform("eventStart", eventStart.value)
            shader.setFloatUniform("intensity", intensity.value)
            shader.setFloatUniform("selected", selected.value)
            shader.setIntUniform("count", regions.size)
            repeat(6) { index ->
                val region = regions.getOrNull(index)
                shader.setFloatUniform("region$index", region?.x ?: 0f, region?.y ?: 0f,
                    region?.radius ?: .1f, region?.seed ?: 0f)
            }
            drawRect(brush)
        }
    }
}

private fun DrawScope.drawInkFallback(regions: List<InkRegion>, selected: Int, time: Float, eventStart: Float, intensity: Float) {
    drawRect(Brush.linearGradient(listOf(Color(0xFF18272E),Color(0xFF708285),Color(0xFF16262D))))
    val cycle = ((time-eventStart).coerceAtLeast(0f)%13f)
    val age = cycle-1.45f
    regions.forEachIndexed { index, region ->
        val center = Offset(size.width*region.x,size.height*region.y)
        val focus = index==selected
        val growth = if(focus) 1f-exp(-age.coerceAtLeast(0f)*.58f) else .85f
        val fade = if(focus) ((11.55f-age)/3.55f).coerceIn(0f,1f) else .5f
        val radius = size.minDimension*region.radius*(.12f+1.02f*growth)
        repeat(12) { layer ->
            val path=Path()
            val expansion = 1f + layer*.019f
            repeat(121) { step ->
                val angle=step/120f*(2*PI).toFloat()
                val irregular=1+.16f*sin(angle*3+region.seed+time*.19f)+.09f*sin(angle*7-region.seed-time*.17f)+.035f*sin(angle*19+time*.13f)
                val r=radius*expansion*irregular
                val point=center+Offset(cos(angle)*r,sin(angle)*r*.64f)
                if(step==0)path.moveTo(point.x,point.y) else path.lineTo(point.x,point.y)
            }
            path.close()
            drawPath(path,Color(0xFF051319).copy(alpha=.11f*fade*(if(focus) (age/.55f).coerceIn(0f,1f) else 1f)))
        }
        if(focus && cycle<1.45f) {
            val lift=size.minDimension*.24f*(1f-(cycle/1.45f).pow(2))
            val point=center-Offset(0f,lift)
            val r=size.minDimension*.024f
            drawCircle(Brush.radialGradient(listOf(Color(0xFFA1B5BD),Color(0xFF13242D),Color(0xFF6B8898)),
                point-Offset(r*.25f,r*.3f),r*1.3f),r,point,alpha=intensity)
        }
        if(focus && age>0f && age<5f) {
            val r=size.minDimension*age*.17f
            drawOval(Color(0xFFB4C7CB).copy(alpha=.22f*exp(-age*.5f)*intensity),
                center-Offset(r,r*.64f),androidx.compose.ui.geometry.Size(r*2,r*1.28f),style=Stroke(1.5f))
        }
    }
}


// Original procedural animation inspired by ink-spread / high-speed water reference.
// No reference video frames or third-party media are bundled.
internal const val INK_SHADER = """
uniform float2 resolution;
uniform float time;
uniform float eventStart;
uniform float selected;
uniform float intensity;
uniform int count;
uniform float4 region0;
uniform float4 region1;
uniform float4 region2;
uniform float4 region3;
uniform float4 region4;
uniform float4 region5;

float hash(float2 p) {
    p=fract(p*float2(123.34,456.21));
    p+=dot(p,p+45.32);
    return fract(p.x*p.y);
}
float noise(float2 p) {
    float2 i=floor(p), f=fract(p);
    float2 u=f*f*f*(f*(f*6.0-15.0)+10.0);
    return mix(mix(hash(i),hash(i+float2(1,0)),u.x),
               mix(hash(i+float2(0,1)),hash(i+float2(1,1)),u.x),u.y);
}
float fbm(float2 p) {
    return .55*noise(p)+.28*noise(p*2.03+7.1)+.17*noise(p*4.09+19.3);
}
float4 regionAt(int i) {
    if(i==0)return region0;
    if(i==1)return region1;
    if(i==2)return region2;
    if(i==3)return region3;
    if(i==4)return region4;
    return region5;
}
float4 drop(float2 p,float2 c,float radius,float stretch) {
    float2 q=(p-c)/float2(radius,radius*stretch);
    float d=dot(q,q);
    if(d>1.0)return float4(0);
    float3 normal=float3(q.x,-q.y,sqrt(max(0.0,1.0-d)));
    float rim=pow(1.0-normal.z,3.0);
    float softbox=exp(-pow((normal.x+.37)*4.0,2.0)-pow((normal.y-.48)*7.0,2.0));
    float reflection=exp(-pow((normal.x-.4)*5.0,2.0)-pow((normal.y+.3)*11.0,2.0));
    float3 color=float3(.018,.026,.035)+float3(.12,.17,.20)*normal.z*normal.z;
    color+=softbox*float3(.84,.91,.93)+reflection*float3(.32,.48,.57);
    color+=rim*float3(.24,.32,.36);
    return float4(color,1.0-smoothstep(.94,1.0,d));
}
half4 main(float2 frag) {
    float minSize=min(resolution.x,resolution.y);
    float2 uv=frag/resolution;
    float2 p=frag/minSize;
    float cycle=mod(max(0.0,time-eventStart),13.0);
    float age=cycle-1.45;
    float2 slope=float2(0);
    float mass=0.0;
    float shadow=0.0;
    float2 active=float2(-10);
    float activeRadius=.2;
    float fineGrain=noise(p*130.0);

    for(int i=0;i<6;i++) {
        if(i<count) {
            float4 r=regionAt(i);
            float2 c=r.xy*resolution/minSize;
            float focus=max(0.0,1.0-abs(selected-float(i)));
            if(focus>.5) { active=c; activeRadius=r.z; }
            float localAge=mix(4.5+sin(time*.18+r.w)*1.2,max(age,0.0),focus);
            float spread=.12+1.02*(1.0-exp(-localAge*.58));
            float fade=mix(.48,1.0-smoothstep(8.0,11.55,age),focus);
            float2 q=(p-c)*float2(1.0,1.58);
            float distance=length(q);
            if(distance<r.z*1.85) {
                float2 v=q/(r.z*spread);
                // Advected coordinates produce lobes, folds and fine feathered branches.
                float curl=1.8*exp(-dot(v,v)*.5)+time*.06+r.w;
                float2 w=float2(v.x*cos(curl)-v.y*sin(curl),v.x*sin(curl)+v.y*cos(curl));
                float2 warp=float2(fbm(w*1.8+r.w+time*.06),fbm(w*1.8+31.7-time*.045))-.5;
                w+=warp*.95;
                float coarse=fbm(w*3.1+r.w);
                float filaments=fbm(w*11.0+warp*3.0);
                float boundary=1.0-length(w)+.44*(coarse-.5)+.18*(filaments-.5);
                float body=smoothstep(-.11,.16,boundary);
                float fray=smoothstep(-.30,.10,boundary)*smoothstep(.28,.7,filaments)*.38;
                float density=(body*(.75+.55*coarse)+fray)*fade;
                density*=mix(1.0,smoothstep(0.0,.55,age),focus);
                mass+=density;
                // Ripples travel outward and decay; they never outline the pigment boundary.
                float front=max(age,0.0)*.17;
                float z=distance-front;
                float envelope=exp(-z*z/.005)*exp(-max(age,0.0)*.48)
                    *smoothstep(0.0,.12,age)*focus*intensity;
                float gradient=(cos(z*62.0)*1.2-sin(z*62.0)*z*8.0)*envelope;
                slope+=q/max(distance,.001)*gradient;
                float depression=exp(-distance*distance/.002)*exp(-max(age,0.0)*3.0)
                    *smoothstep(0.0,.08,age)*focus;
                shadow+=depression*.4*intensity;
            }
        }
    }
    float3 normal=normalize(float3(-slope.x,-slope.y*.7,1.0));
    float reflection=pow(max(dot(normal,normalize(float3(-.3,-.45,1.0))),0.0),24.0);
    float wash=exp(-dot((uv-float2(.48,.32))*float2(1.3,2.7),
                       (uv-float2(.48,.32))*float2(1.3,2.7))*2.0);
    float3 water=float3(.055,.077,.086)+wash*float3(.20,.235,.24);
    water+=reflection*float3(.16,.19,.2);
    water+=float3(.015,.019,.02)*(fineGrain-.5);
    water-=shadow;
    float opacity=1.0-exp(-mass*2.4);
    float3 pigment=float3(.009,.019,.024)+float3(.025,.04,.045)*exp(-mass*2.0);
    float3 color=mix(water,pigment,opacity);
    // Reflections belong to the water above the ink, not a luminous border around it.
    color+=reflection*length(slope)*float3(.15,.19,.21)*intensity;

    if(selected>=0.0 && count>0 && intensity>.01) {
        float2 surface=(p-active)*float2(1.0,1.58);
        float radius=length(surface);
        if(cycle<1.45) {
            float fall=cycle/1.45;
            float lift=.24*(1.0-fall*fall);
            float4 bead=drop(p,active-float2(0,lift),.024,1.12+.22*fall);
            color=mix(color,bead.rgb,bead.a*intensity*smoothstep(0.0,.2,cycle));
            color*=1.0-.17*exp(-radius*radius/.0015)*fall*intensity;
        }
        if(age>0.0 && age<1.1) {
            float u=age/1.1;
            float crownRadius=.022+.075*sqrt(u);
            float theta=atan(surface.y,surface.x);
            float lip=crownRadius*(1.0+.08*cos(theta*9.0)*sin(u*3.14159));
            float lipWidth=.006+.006*u;
            float crown=exp(-pow((radius-lip)/lipWidth,2.0))*sin(u*3.14159);
            color+=crown*float3(.19,.25,.27)*intensity*(.4+.6*max(0.0,-surface.y/max(radius,.001)));
        }
        if(age>.28 && age<2.35) {
            float u=(age-.28)/2.07;
            float height=.17*sin(u*3.14159);
            float2 q=p-active;
            float along=clamp(-q.y/max(height,.001),0.0,1.0);
            float width=.006+.012*pow(1.0-along,2.0);
            float jet=(1.0-smoothstep(width,width+.003,abs(q.x)))
                *smoothstep(-height-.01,-height+.01,q.y)*(1.0-smoothstep(-.006,.008,q.y));
            float glass=.18+.55*exp(-pow((q.x/width+.3)*3.0,2.0));
            color=mix(color,float3(.07,.11,.14)+glass*float3(.22,.29,.32),jet*intensity);
            float4 bead=drop(p,active-float2(0,height+.013),.018,.95);
            color=mix(color,bead.rgb,bead.a*intensity*sin(u*3.14159));
            float satelliteHeight=height+.055*sin(u*3.14159);
            float4 satellite=drop(p,active+float2(.012*sin(u*5.0),-satelliteHeight-.037),.009,1.03);
            color=mix(color,satellite.rgb,satellite.a*intensity*sin(u*3.14159));
        }
    }
    color*=1.0-.32*smoothstep(.28,.8,length((uv-.5)*float2(.95,1.0)));
    return half4(half3(clamp(color,0.0,1.0)),1.0);
}
"""
