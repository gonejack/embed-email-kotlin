import cmd.EmbedEmailException
import org.apache.commons.cli.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val opts = Options()
    val verbose = Option("v", "verbose", false, "verbose printing").also { opts.addOption(it) }

    try {
        val parsed = DefaultParser().parse(opts, args)

        cmd.EmbedEmail(
            verbose = verbose.getValue("false").toBoolean()
        ).run(parsed.argList)

        exitProcess(0)
    } catch (e: ParseException) {
        println(e.message)
        HelpFormatter().printHelp("embed-email", opts)
    } catch (e: EmbedEmailException) {
        println(e.message)
        HelpFormatter().printHelp("embed-email", opts)
    }
}
