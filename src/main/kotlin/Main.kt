import cmd.EmbedEmailException
import org.apache.commons.cli.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val opts = Options().apply {
        addOption(Option("v", "verbose", false, "verbose printing"))
    }

    try {
        val parsed = DefaultParser().parse(opts, args)

        cmd.EmbedEmail(
            verbose = parsed.hasOption("v")
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
