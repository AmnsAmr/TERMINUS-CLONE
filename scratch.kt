import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
fun main() {
    val newUrl = "http://localhost".toHttpUrlOrNull()!!
    println(newUrl.newBuilder().addEncodedPathSegments("").build())
}
