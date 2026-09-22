import androidx.media3.exoplayer.ExoPlayer

fun main() {
    val clazz = ExoPlayer::class.java
    clazz.methods.forEach { method ->
        if (method.name.lowercase().contains("preload")) {
            println(method)
        }
    }
}
