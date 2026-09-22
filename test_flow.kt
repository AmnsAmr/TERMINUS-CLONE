import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

fun main() = runBlocking {
    val f1 = flowOf(1, 2, 3)
    val f2 = MutableStateFlow(0)
    
    val combined = combine(f1, f2) { a, b -> a + b }
    try {
        combined.flowOn(Dispatchers.Default).collect {
            println(it)
        }
        println("Success!")
    } catch(e: Exception) {
        println("Exception: ${e.message}")
    }
}
