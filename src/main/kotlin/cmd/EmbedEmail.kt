package cmd

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.sf.jmimemagic.Magic
import okhttp3.*
import org.jsoup.Jsoup
import org.simplejavamail.api.email.Email
import org.simplejavamail.converter.EmailConverter
import org.simplejavamail.email.EmailBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.io.path.extension
import kotlin.io.path.forEachDirectoryEntry

class EmbedEmailException : Exception {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}

class EmbedEmail(
    val verbose: Boolean
) {
    private val client = OkHttpClient.Builder().apply {
        callTimeout(Duration.of(3, ChronoUnit.MINUTES))
        System.getenv("http_proxy")?.also { p ->
            URI(p).also { u ->
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(u.host, u.port))
                println("using proxy $proxy")
                proxy(proxy)
            }
        }
    }.build()
    private val header =
        Headers.headersOf(
            "user-agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:93.0) Gecko/20100101 Firefox/93.0"
        )
    private val media = "./media"

    fun run(args: List<String>) {
        var emls = args.toList()

        if (emls.isEmpty() || emls.contains("*.eml")) {
            emls = glob("*.eml").filter { !it.endsWith(".embed.eml") }
        }
        if (emls.isEmpty()) {
            throw EmbedEmailException("no .eml file given")
        }

        emls.forEach { eml ->
            println("process $eml")
            File(eml).inputStream().use { i ->
                val email = process(i)
                val output = eml.removeSuffix(".eml").plus(".embed.eml")

                File(output).outputStream().use { o ->
                    EmailConverter.emailToMimeMessage(email).writeTo(o)
                }
            }
        }
    }

    private fun process(inputStream: InputStream): Email {
        val raw = EmailConverter.emlToEmail(inputStream)
        val doc = raw.htmlText?.let { Jsoup.parse(it, Charsets.UTF_8.toString()) }
            ?: throw EmbedEmailException("cannot parse as HTML: ${raw.htmlText}")
        val urls =
            doc.select("img").map { it.attr("src") }.filter { it.startsWith("http://") || it.startsWith("https://") }
                .toSet()
        val eml = EmailBuilder.copying(raw)
        val saves = download(urls)
        val embed = mutableMapOf<String, String>()

        doc.select("img").forEach { img ->
            val src = img.attr("src")
            if (!saves.containsKey(src)) {
                return@forEach
            }
            val cid = let {
                if (!embed.containsKey(src)) {
                    val f = File(saves[src]!!)
                    val m = Magic.getMagicMatch(f, true)
                    val cid = f.name.removeSuffix(f.extension).plus(m.extension)
                    f.inputStream().use {
                        eml.withEmbeddedImage(cid, it.readAllBytes(), m.mimeType)
                        embed[src] = cid
                    }
                }
                embed[src]
            }
            img.attr("src", "cid:${cid}")
        }
        eml.withHTMLText(doc.html())

        return eml.buildEmail()
    }

    private fun download(list: Set<String>): Map<String, String> {
        val semap = Semaphore(3)
        val saves = mutableMapOf<String, String>()

        runBlocking {
            list.forEach { url ->
                semap.withPermit {
                    launch {
                        downloadOne(url)?.also { saves[url] = it }
                    }
                }
            }
        }

        return saves
    }

    private suspend fun downloadOne(url: String): String? {
        try {
            verbose.ifTrue {
                println("download $url with Thread#${Thread.currentThread().name} start")
            }

            val reqs = Request.Builder().url(url).headers(header).build()
            val resp = suspendCoroutine<Response> { c ->
                client.newCall(reqs).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        c.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        c.resume(response)
                    }
                })
            }

            File(media).mkdirs()

            val name = listOf(md5(url).toHex(), Path.of(url).extension).joinToString(".")
            val file = Paths.get(media, name).toFile()
            resp.body?.byteStream().use { i ->
                file.outputStream().use { o ->
                    i?.copyTo(o)
                }
            }

            verbose.ifTrue {
                println("download $url with Thread#${Thread.currentThread().name} done")
            }

            return file.absolutePath
        } catch (e: Exception) {
            println("download $url failed: ${e.message}")

            return null
        }
    }
}

fun md5(str: String): ByteArray = MessageDigest.getInstance("MD5").digest(str.toByteArray(Charsets.UTF_8))
fun ByteArray.toHex() = joinToString(separator = "") { byte -> "%02x".format(byte) }
fun glob(glob: String): List<String> {
    return mutableListOf<String>().apply { Path.of(".").forEachDirectoryEntry(glob) { add(it.toString()) } }
}

inline fun Boolean?.ifTrue(block: Boolean.() -> Unit): Boolean? {
    if (this == true) {
        block()
    }
    return this
}

inline fun Boolean?.ifFalse(block: Boolean?.() -> Unit): Boolean? {
    if (null == this || !this) {
        block()
    }
    return this
}
