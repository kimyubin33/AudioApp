package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

/** 콘텐츠 형식은 피드 안에서 고르고, 장소와 작성에는 각자의 입구를 둔다. */
enum class MainTab(val label: String) {
    Feed("피드"), Places("지도"), Create("창작"), Subscriptions("구독"), Library("보관함")
}

@Composable
fun BottomNav(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        InkDivider()
        Row(Modifier.fillMaxWidth().heightIn(min=80.dp).selectableGroup(),verticalAlignment=Alignment.CenterVertically) {
            MainTab.entries.forEach { tab ->
                val active = tab == selected
                val color = if(active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).heightIn(min=80.dp)
                        .selectable(selected=active,role=if(tab==MainTab.Create) Role.Button else Role.Tab,onClick={onSelect(tab)}).padding(vertical=8.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)
                ) {
                    val symbol = when(tab) {
                        MainTab.Feed -> AlbumSymbol.Feed
                        MainTab.Places -> AlbumSymbol.Map
                        MainTab.Create -> AlbumSymbol.Create
                        MainTab.Subscriptions -> AlbumSymbol.Person
                        MainTab.Library -> AlbumSymbol.Archive
                    }
                    Box(Modifier.size(40.dp).then(if(tab==MainTab.Create) Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface) else Modifier),
                        contentAlignment=Alignment.Center) {
                        AlbumIcon(symbol,Modifier.size(if(tab==MainTab.Create) 27.dp else 26.dp),
                            if(tab==MainTab.Create) MaterialTheme.colorScheme.surface else color)
                    }
                    Text(tab.label,style=MaterialTheme.typography.labelMedium,color=color,maxLines=1)
                }
            }
        }
    }
}

@Preview(showBackground=true,widthDp=360,heightDp=100)
@Composable
private fun BottomNavPreview() { AudioAppTheme { BottomNav(MainTab.Feed,{}) } }
