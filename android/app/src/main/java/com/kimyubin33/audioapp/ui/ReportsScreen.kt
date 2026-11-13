package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.data.AccountActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private data class ReportEntry(val id:String,val target:String,val reason:String,val status:String,val resolution:String?,val created:Long)
private val reportStates=mapOf("open" to "접수","reviewing" to "검토 중","resolved" to "조치 완료","dismissed" to "기각")
private val reportReasons=mapOf("spam" to "스팸","inappropriate" to "부적절한 내용","copyright" to "저작권 침해","missing_ai_label" to "AI 표시","other" to "기타")

@Composable
fun ReportsScreen(userId:String,onBack:()->Unit,onPolicies:()->Unit,loadPage:(suspend (String?)->String)?=null) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var entries by remember(userId) { mutableStateOf(emptyList<ReportEntry>()) }
    var cursor by remember(userId) { mutableStateOf<String?>(null) }
    var loading by remember(userId) { mutableStateOf(false) }
    var loaded by remember(userId) { mutableStateOf(false) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    fun load(reset:Boolean) {
        if(loading)return
        loading=true;error=null
        scope.launch {
            try {
                val next=if(reset)null else cursor
                val raw=loadPage?.invoke(next) ?: AccountActions.withLogin(context) { it.myReports(next) }
                val json=JSONObject(raw);val array=json.getJSONArray("items")
                val page=(0 until array.length()).map { i ->
                    val row=array.getJSONObject(i)
                    ReportEntry(row.getString("id"),
                        if(!row.isNull("title"))row.getString("title") else row.optString("creator_id","크리에이터"),
                        row.getString("reason"),row.getString("status"),
                        if(row.isNull("resolution"))null else row.getString("resolution"),row.getLong("created_at"))
                }
                entries=(if(reset)page else entries+page).distinctBy { it.id }
                cursor=if(json.isNull("next_cursor"))null else json.getString("next_cursor")
                loaded=true
            } catch(e:CancellationException){throw e}
            catch(_:Exception){error="신고 내역을 불러오지 못했습니다. 다시 시도해 주세요."}
            finally{loading=false}
        }
    }
    LaunchedEffect(userId){load(true)}
    Column(Modifier.fillMaxSize()) {
        AccountTopBar("신고 내역",onBack)
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text("신고 상태와 처리 사유를 확인할 수 있습니다.",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={load(true)},enabled=!loading){Text("새로고침")}
            }
            if(loaded&&entries.isEmpty())item { Text("접수한 신고가 없습니다.") }
            items(entries,key={it.id}) { report ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(report.target,style=MaterialTheme.typography.titleMedium)
                        Text((reportStates[report.status]?:"확인 중")+" · "+(reportReasons[report.reason]?:"신고"))
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(report.created*1000)),
                            style=MaterialTheme.typography.bodySmall)
                        report.resolution?.let { Text(it) }
                        Text("접수 번호: "+report.id,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { message -> item { Text(message,color=MaterialTheme.colorScheme.error);TextButton(onClick={load(!loaded)},enabled=!loading){Text("다시 시도")} } }
            if(loading)item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if(cursor!=null)item { TextButton(onClick={load(false)},enabled=!loading){Text("더 보기")} }
            item { Text("이의 제기는 접수 번호와 함께 운영 문의로 전달해 주세요.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onPolicies){Text("약관 및 문의")} }
        }
    }
}
