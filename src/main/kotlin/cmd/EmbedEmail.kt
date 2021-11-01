package cmd

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.sf.jmimemagic.Magic
import okhttp3.*
import org.jsoup.Jsoup
import org.simplejavamail.api.email.AttachmentResource
import org.simplejavamail.api.email.Email
import org.simplejavamail.converter.EmailConverter
import org.simplejavamail.email.EmailBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.time.temporal.ChronoUnit
import javax.activation.FileDataSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.io.path.extension

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
        var emlList = args.toList()

        if (emlList.isEmpty() || emlList.contains("*.eml")) {
            emlList = glob("*.eml")
        }

        if (emlList.isEmpty()) {
            throw EmbedEmailException("no .eml given")
        }

        emlList.forEach { eml ->
            println("process $eml")
            File(eml).inputStream().use { i ->
                val embed = embedOne(i)
                val out = eml.removeSuffix(".eml") + ".embed.eml"
                File(out).outputStream().use { o ->
                    EmailConverter.emailToMimeMessage(embed).writeTo(o)
                }
            }
        }
    }

    private fun embedOne(ins: InputStream): Email {
        val raw = EmailConverter.emlToEmail(ins)
        val doc = raw.htmlText?.let { Jsoup.parse(it, Charsets.UTF_8.toString()) }
            ?: throw EmbedEmailException("cannot parse as HTML: ${raw.htmlText}")
        val urls =
            doc.select("img").map { it.attr("src") }.filter { it.startsWith("http://") || it.startsWith("https://") }
                .toSet()


        val saves = download(urls)
        val embed = mutableMapOf<String, AttachmentResource>().also { m ->
            raw.attachments.forEach { a ->
                m[a.name.orEmpty()] = a
            }
        }

        doc.select("img").forEach { img ->
            val src = img.attr("src")
            if (saves.containsKey(src)) {
                val res = let {
                    if (!embed.containsKey(src)) {
                        val file = File(saves[src]!!)
                        val name = file.toPath().fileName.toString().let {
                            val match = Magic.getMagicMatch(file, true)
                            val ext = ".${match.extension}"
                            it.removeSuffix(ext).plus(ext)
                        }
                        embed[src] = AttachmentResource(name, FileDataSource(saves[src]))
                    }
                    embed[src]
                }
                img.attr("src", "cid:${res?.name}")
            }
        }

        val eml = EmailBuilder.copying(raw).apply {
            withEmbeddedImages(embed.map { it.value })
            withHTMLText(doc.html())
        }

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
                println("download $url with ${Thread.currentThread().name} start")
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
                println("download $url with ${Thread.currentThread().name} done")
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
    val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
    return Files.walk(Path.of(".")).filter { matcher.matches(it.fileName) }.map { it.toString() }.toList()
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
