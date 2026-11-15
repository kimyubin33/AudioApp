package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.kimyubin33.audioapp.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class AlbumSymbol { Feed, Map, Create, Person, Archive, Search, Camera, Pin, Audio, Video, Edit, Lock, Car }

/** 시안의 가는 먹 선 아이콘. 장식용 Canvas에는 별도 접근성 노드를 만들지 않는다. */
@Composable
internal fun AlbumIcon(symbol: AlbumSymbol, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x*u,y*u), Offset(x2*u,y2*u), strokeWidth=stroke.width, cap=StrokeCap.Round)
        fun round(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) =
            drawRoundRect(color, Offset(x*u,y*u), Size(w*u,h*u), CornerRadius(r*u), style=stroke)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color,r*u,Offset(x*u,y*u),style=stroke)
        fun path(vararg points: Pair<Float, Float>) {
            drawPath(Path().apply { points.forEachIndexed { i,p -> if(i==0) moveTo(p.first*u,p.second*u) else lineTo(p.first*u,p.second*u) } },color,style=stroke)
        }
        when(symbol) {
            AlbumSymbol.Feed -> { round(3f,3f,18f,18f); circle(8f,8f,1.5f); path(4f to 18f,10f to 12f,14f to 16f,17f to 13f,21f to 17f) }
            AlbumSymbol.Map -> { circle(12f,12f,9f); drawOval(color,Offset(8f*u,3f*u),Size(8f*u,18f*u),style=stroke); line(3f,12f,21f,12f); path(6f to 6f,10f to 8f,14f to 6f,18f to 8f) }
            AlbumSymbol.Create -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
            AlbumSymbol.Person -> { circle(12f,7f,4f); drawPath(Path().apply { moveTo(4f*u,21f*u); lineTo(4f*u,19f*u); cubicTo(4f*u,12f*u,20f*u,12f*u,20f*u,19f*u); lineTo(20f*u,21f*u); close() },color,style=stroke) }
            AlbumSymbol.Archive -> { round(3f,8f,18f,13f); path(3f to 8f,6f to 3f,18f to 3f,21f to 8f); line(3f,11f,21f,11f); drawArc(color,0f,180f,false,Offset(9f*u,13f*u),Size(6f*u,4f*u),style=stroke) }
            AlbumSymbol.Search -> { circle(10f,10f,6.5f); line(15f,15f,21f,21f) }
            AlbumSymbol.Camera -> { round(2.5f,6f,19f,15f,3f); path(7f to 6f,9f to 3f,15f to 3f,17f to 6f); circle(12f,13f,4f) }
            AlbumSymbol.Pin -> { drawPath(Path().apply { moveTo(12f*u,22f*u); cubicTo(9f*u,17f*u,4f*u,12f*u,5f*u,8f*u); cubicTo(7f*u,-1f*u,20f*u,1f*u,19f*u,10f*u); cubicTo(19f*u,14f*u,15f*u,19f*u,12f*u,22f*u) },color,style=stroke); circle(12f,8f,2.5f) }
            AlbumSymbol.Audio -> { round(8f,2f,8f,13f,4f); drawArc(color,0f,180f,false,Offset(5f*u,6f*u),Size(14f*u,13f*u),style=stroke); line(12f,19f,12f,22f); line(8f,22f,16f,22f) }
            AlbumSymbol.Video -> { round(5f,2f,14f,20f,3f); path(10f to 8f,15f to 12f,10f to 16f,10f to 8f) }
            AlbumSymbol.Edit -> { path(4f to 16f,16f to 4f,20f to 8f,8f to 20f,3f to 21f,4f to 16f); line(13f,7f,17f,11f) }
            AlbumSymbol.Lock -> { round(5f,10f,14f,12f); drawArc(color,180f,180f,false,Offset(8f*u,2f*u),Size(8f*u,15f*u),style=stroke); line(12f,15f,12f,18f) }
            AlbumSymbol.Car -> { round(3f,10f,18f,10f); path(4f to 10f,7f to 4f,17f to 4f,20f to 10f); line(6f,20f,6f,22f); line(18f,20f,18f,22f); line(6f,14f,8f,14f); line(16f,14f,18f,14f) }
        }
    }
}

@Composable
internal fun AlbumHeader(onSearch: (() -> Unit)? = null, onCreate: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=12.dp,bottom=12.dp), verticalAlignment=Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = "A & M Audio",
            modifier = Modifier.width(64.dp).height(40.dp),
        )
        Spacer(Modifier.weight(1f))
        if(onSearch!=null) IconButton(onClick=onSearch,modifier=Modifier.semantics { contentDescription="이야기 검색" }) {
            AlbumIcon(AlbumSymbol.Search,Modifier.size(25.dp))
        }
        if(onCreate!=null) FilledIconButton(onClick=onCreate,modifier=Modifier.semantics { contentDescription="New Moment" }) {
            AlbumIcon(AlbumSymbol.Camera,Modifier.size(22.dp),MaterialTheme.colorScheme.onPrimary)
        }
    }
}
