import kotlinx.coroutines.*

suspend fun getLikedSongs(): List<String> = withContext(Dispatchers.Default) {
    val b = true
    if (b) return@withContext emptyList()
    listOf("a")
}

fun main() = runBlocking {
    println(getLikedSongs())
}
